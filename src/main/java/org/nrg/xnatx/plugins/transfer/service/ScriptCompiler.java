package org.nrg.xnatx.plugins.transfer.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.nrg.dicom.mizer.exceptions.MizerException;
import org.nrg.dicom.mizer.service.MizerService;
import org.nrg.xnatx.plugins.transfer.model.ScriptBinding;
import org.nrg.xnatx.plugins.transfer.model.ScriptValidationResult;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compiles and validates a custom DicomEdit anonymization script for Reimport.
 *
 * <p>Two layers, deliberately separated:
 * <ul>
 *   <li><b>Pure text</b> — placeholder extraction, binding, value sanitize, and {@code ${csv.*}}
 *       substitution. No DicomEdit involvement; stateless and thread-safe.</li>
 *   <li><b>Engine parse</b> — compile-without-apply via the {@link MizerService} bean (the same anonymizer
 *       {@code GradualDicomImporter} uses), to surface syntax errors without a DICOM file. Going through the
 *       service interface keeps this robust to the deployed dicom-edit6 version.</li>
 * </ul>
 *
 * <p>The thread-safety restriction (version + verb deny-list) is plugin-side text detection on the
 * Mizer-validated script, so it needs no dicom-edit6 internals. Limits/charset/deny-list all come from
 * {@link BatchTransferPolicy} (a single source of truth, admin-tunable).
 */
@Service
@Slf4j
public class ScriptCompiler {

    /** {@code ${csv.<column>}}, column captured in group 1. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{csv\\.([A-Za-z0-9_]+)\\}");

    /** The script's {@code version "x.y"} declaration; the quoted value is matched even through the parser. */
    private static final Pattern VERSION_DECL = Pattern.compile("(?m)^\\s*version\\s+\"([0-9.]+)\"");

    private final MizerService mizer;
    private final BatchTransferPolicy prefs;

    @Autowired
    public ScriptCompiler(final MizerService mizer, final BatchTransferPolicy prefs) {
        this.mizer = mizer;
        this.prefs = prefs;
    }

    // ---- Layer 1: pure text -------------------------------------------------------------------------

    /** The distinct {@code csv.} column names referenced by {@code ${csv.<column>}} placeholders. */
    public Set<String> extractPlaceholders(final String template) {
        final Set<String> columns = new LinkedHashSet<>();
        if (template != null) {
            final Matcher m = PLACEHOLDER.matcher(template);
            while (m.find()) {
                columns.add(m.group(1));
            }
        }
        return columns;
    }

    /** Placeholders in {@code template} with no matching column in {@code availableColumns}. */
    public List<String> unboundPlaceholders(final String template, final Set<String> availableColumns) {
        final List<String> unbound = new ArrayList<>();
        for (final String column : extractPlaceholders(template)) {
            if (availableColumns == null || !availableColumns.contains(column)) {
                unbound.add(column);
            }
        }
        return unbound;
    }

    /** True if {@code value} contains only characters in the configured safe charset (null/empty are safe). */
    public boolean isValueSafe(final String value) {
        return value == null || safeValuePattern().matcher(value).matches();
    }

    /**
     * Substitute {@code ${csv.<column>}} placeholders from {@code row}. Substitution is namespaced to
     * {@code csv.} (a non-{@code csv.} key resolves to null, so DicomEdit-native {@code ${var}} references
     * pass through untouched) and non-recursive (a value can never introduce a new placeholder).
     */
    public String compile(final String template, final Map<String, String> row) {
        if (template == null) {
            return null;
        }
        final Map<String, String> safeRow = row == null ? Collections.<String, String>emptyMap() : row;
        final StringLookup lookup = new StringLookup() {
            @Override
            public String lookup(final String key) {
                if (key == null || !key.startsWith("csv.")) {
                    return null;
                }
                return safeRow.get(key.substring(4));
            }
        };
        final StringSubstitutor substitutor = new StringSubstitutor(lookup);
        substitutor.setEnableUndefinedVariableException(false);
        substitutor.setDisableSubstitutionInValues(true);
        return substitutor.replace(template);
    }

    // ---- Layer 2: engine parse (compile-without-apply) ----------------------------------------------

    /**
     * Parse {@code script} without applying it to a DICOM file. Goes through the {@link MizerService}
     * interface only, so this stays robust to the deployed dicom-edit6 version. {@code createContext} only wraps
     * the script; {@code getReferencedVariables} parses it and throws on a syntax error. ({@code getScriptTags}
     * also parses, but DicomEdit 6 logs a syntax error there and returns an empty set.)
     */
    public void parseValidate(final String script) throws ScriptValidationException {
        try {
            mizer.getReferencedVariables(mizer.createContext(null, null, null, 0L, script, false, false));
        } catch (MizerException e) {
            throw new ScriptValidationException(HttpStatus.BAD_REQUEST,
                    "The anonymization script could not be parsed: " + innermostMessage(e));
        } catch (RuntimeException e) {
            // Mizer declares MizerException but also throws unchecked exceptions on malformed input: a
            // script whose version declaration it cannot split reaches AbstractMizer.parseVersionString
            // and fails with ArrayIndexOutOfBoundsException. This endpoint validates text the user is
            // still typing, so no input may surface as a 500 - report it as a parse failure instead.
            log.warn("Anonymization script rejected: the mizer engine threw {} while parsing it",
                    e.getClass().getSimpleName(), e);
            throw new ScriptValidationException(HttpStatus.BAD_REQUEST,
                    "The anonymization script could not be parsed. Check that it declares a version, "
                            + "for example version \"6.6\".");
        }
    }

    /**
     * The innermost non-blank message in {@code e}'s cause chain. DicomEdit's "Failed to parse at line:column
     * due to ..." detail sits beneath its "Failed creating DE6Parser" wrappers.
     */
    private static String innermostMessage(final Throwable e) {
        final List<Throwable> chain = ExceptionUtils.getThrowableList(e);
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (StringUtils.isNotBlank(chain.get(i).getMessage())) {
                return chain.get(i).getMessage();
            }
        }
        return e.toString();
    }

    // ---- Restriction (text detection on a Mizer-validated script) -----------------------------------

    /**
     * Restriction violations: a missing/disallowed {@code version} declaration, or any deny-listed verb used
     * in call form, either {@code verb[...]} (DicomEdit 6 function syntax) or {@code verb(...)}. Detection is
     * plugin-side text scanning — the version is read from the raw script (so its
     * quoted value survives), and verbs are matched after stripping string literals and {@code //} comments
     * so a verb named in a comment or value does not false-trip. Empty list = no violations.
     */
    public List<String> restrictionViolations(final String script) {
        final List<String> violations = new ArrayList<>();
        if (script == null) {
            return violations;
        }

        final Matcher version = VERSION_DECL.matcher(script);
        if (!version.find()) {
            violations.add("The script must declare a version (e.g. version \"6.1\").");
        } else if (!Pattern.compile(prefs.getAllowedAnonVersionPattern()).matcher(version.group(1)).matches()) {
            violations.add("Script version " + version.group(1) + " is not allowed (must match "
                    + prefs.getAllowedAnonVersionPattern() + ").");
        }

        final String scannable = stripStringsAndComments(script);
        for (final String verb : restrictedVerbs()) {
            if (Pattern.compile("\\b" + Pattern.quote(verb) + "\\s*[\\[(]").matcher(scannable).find()) {
                violations.add("The script uses restricted command '" + verb
                        + "'. Use hashUID for deterministic UID remapping.");
            }
        }
        return violations;
    }

    // ---- Orchestration: submit-time enforcement -----------------------------------------------------

    /**
     * Authoritative submit-time validation of a batch-level {@code anonScript} against its Reimport
     * requests. Order: size, restriction (version + verbs), per-request binding + value sanitize, then one
     * representative compile-without-apply parse (values are sanitized, so a single substituted form is
     * authoritative for every row). Throws the first failure as a {@link ScriptValidationException} carrying
     * the HTTP status the controller should return.
     */
    public void validateBatch(final String anonScript, final List<TransferRequest> requests)
            throws ScriptValidationException {
        if (StringUtils.isBlank(anonScript) || requests == null || requests.isEmpty()) {
            return;
        }

        checkSize(anonScript);

        final List<String> violations = restrictionViolations(anonScript);
        if (!violations.isEmpty()) {
            throw new ScriptValidationException(HttpStatus.BAD_REQUEST, String.join(" ", violations));
        }

        final Set<String> placeholders = extractPlaceholders(anonScript);
        final Pattern safe = safeValuePattern();
        for (final TransferRequest request : requests) {
            final Map<String, String> row = request.getCsvValues() == null
                    ? Collections.<String, String>emptyMap() : request.getCsvValues();
            for (final String column : placeholders) {
                if (!row.containsKey(column)) {
                    throw new ScriptValidationException(HttpStatus.BAD_REQUEST,
                            "Unbound placeholder ${csv." + column + "} for item " + request.getId()
                                    + " (no matching value supplied).");
                }
            }
            for (final Map.Entry<String, String> cell : row.entrySet()) {
                final String value = cell.getValue();
                if (value != null && !safe.matcher(value).matches()) {
                    throw new ScriptValidationException(HttpStatus.BAD_REQUEST,
                            "Value for '" + cell.getKey() + "' on item " + request.getId()
                                    + " contains characters outside the allowed set ("
                                    + prefs.getAnonValueCharset() + ").");
                }
            }
        }

        // Sanitized values can't introduce new syntax, so one representative substituted form validates the
        // whole batch.
        parseValidate(compile(anonScript, requests.get(0).getCsvValues()));
    }

    // ---- Advisory verdict (for /validate/script) ----------------------------------------------------

    /**
     * Non-throwing advisory validation for the {@code /validate/script} endpoint. Unlike {@link #validateBatch}
     * (fail-fast, for submit), this gathers <em>all</em> feedback into one verdict so the UI can surface it at
     * once: size + parse {@code errors}, the thread-safety {@code restrictionViolations}, the declared
     * placeholders, and — when {@code manifestColumns} is supplied — the {@code binding}. It reuses the same
     * seams submit enforces ({@link #checkSize}, {@link #restrictionViolations}, {@link #parseValidate},
     * {@link #unboundPlaceholders}), so {@code valid == true} means the script would clear submit for these
     * columns.
     *
     * <p>The raw template's {@code ${csv.*}} placeholders aren't DE6 tokens, so a templated script is parsed
     * against a neutral sentinel (a bare {@code 0}, which survives both string and numeric contexts); with no
     * {@code manifestColumns} the verdict notes that a manifest is needed to fully validate substitution.
     */
    public ScriptValidationResult validate(final String anonScript, final List<String> manifestColumns) {
        final ScriptValidationResult result = new ScriptValidationResult();
        result.setErrors(new ArrayList<String>());
        result.setRestrictionViolations(new ArrayList<String>());

        if (StringUtils.isBlank(anonScript)) {
            result.setPlaceholders(Collections.<String>emptyList());
            result.getErrors().add("No anonymization script supplied.");
            result.setValid(false);
            return result;
        }

        result.setSizeBytes(anonScript.getBytes(StandardCharsets.UTF_8).length);

        final Matcher version = VERSION_DECL.matcher(anonScript);
        if (version.find()) {
            result.setDialect("DicomEdit " + version.group(1));
        }

        final Set<String> placeholderColumns = extractPlaceholders(anonScript);
        result.setPlaceholders(new ArrayList<>(placeholderColumns));

        try {
            checkSize(anonScript);
        } catch (ScriptValidationException e) {
            result.getErrors().add(e.getMessage());
        }

        result.setRestrictionViolations(restrictionViolations(anonScript));

        String toParse = anonScript;
        if (!placeholderColumns.isEmpty()) {
            final Map<String, String> sentinel = new HashMap<>();
            for (final String column : placeholderColumns) {
                sentinel.put(column, "0");
            }
            toParse = compile(anonScript, sentinel);
            if (manifestColumns == null) {
                result.setMessage("Provide a manifest to fully validate ${csv.*} substitution.");
            }
        }
        // Only hand the script to the engine when its version declaration is present and allowed. The
        // engine keys off that declaration to pick a parser, so without it the call can only fail - and
        // the violation is already reported above. This path deliberately does not short-circuit like
        // validateBatch does, so the guard has to be here rather than an early return.
        if (hasAllowedVersion(anonScript)) {
            try {
                parseValidate(toParse);
            } catch (ScriptValidationException e) {
                result.getErrors().add(e.getMessage());
            }
        }

        if (manifestColumns != null) {
            final List<String> unbound = unboundPlaceholders(anonScript, new LinkedHashSet<>(manifestColumns));
            result.setBinding(new ScriptBinding(unbound.isEmpty(), unbound));
        }

        final boolean bindingOk = result.getBinding() == null || result.getBinding().isBound();
        result.setValid(result.getErrors().isEmpty()
                && result.getRestrictionViolations().isEmpty()
                && bindingOk);
        return result;
    }

    /**
     * Does the script carry a {@code version} declaration this build allows? The engine selects its parser
     * from that declaration, so callers use this to avoid an engine parse that cannot succeed.
     */
    private boolean hasAllowedVersion(final String script) {
        if (script == null) {
            return false;
        }
        final Matcher version = VERSION_DECL.matcher(script);
        return version.find()
                && Pattern.compile(prefs.getAllowedAnonVersionPattern()).matcher(version.group(1)).matches();
    }

    /** Reject a script larger than the configured byte cap. */
    public void checkSize(final String script) throws ScriptValidationException {
        if (script == null) {
            return;
        }
        final int max = prefs.getMaxAnonScriptBytes();
        final int size = script.getBytes(StandardCharsets.UTF_8).length;
        if (size > max) {
            throw new ScriptValidationException(HttpStatus.BAD_REQUEST,
                    "The anonymization script is " + size + " bytes, exceeding the " + max + "-byte limit.");
        }
    }

    // ---- internals ----------------------------------------------------------------------------------

    private Pattern safeValuePattern() {
        return Pattern.compile("^[" + prefs.getAnonValueCharset() + "]*$");
    }

    private List<String> restrictedVerbs() {
        final List<String> verbs = new ArrayList<>();
        final String raw = prefs.getRestrictedAnonVerbs();
        if (StringUtils.isNotBlank(raw)) {
            for (final String token : raw.split("[,\\s]+")) {
                if (StringUtils.isNotBlank(token)) {
                    verbs.add(token.trim());
                }
            }
        }
        return verbs;
    }

    /**
     * Drop string-literal contents (respecting {@code \"} escapes) and {@code //} line comments so a verb
     * name appearing only in a value or comment is not mistaken for a call. Leaves call structure intact.
     */
    private static String stripStringsAndComments(final String script) {
        final StringBuilder out = new StringBuilder(script.length());
        boolean inString = false;
        for (int i = 0; i < script.length(); i++) {
            final char c = script.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < script.length()) {
                    i++;                 // skip the escaped character
                } else if (c == '"') {
                    inString = false;
                }
                continue;                // drop string contents
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;                 // skip to end of line
                }
                out.append('\n');
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}
