package org.nrg.xnatx.plugins.transfer.api;

import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferRunExistsException;
import org.nrg.xnatx.plugins.transfer.model.BatchTransfer;
import org.nrg.xnatx.plugins.transfer.model.ManifestRow;
import org.nrg.xnatx.plugins.transfer.model.ManifestSummary;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationRequest;
import org.nrg.xnatx.plugins.transfer.model.ManifestValidationResult;
import org.nrg.xnatx.plugins.transfer.model.ScriptValidationRequest;
import org.nrg.xnatx.plugins.transfer.model.TransferCapabilities;
import org.nrg.xnatx.plugins.transfer.model.TransferMode;
import org.nrg.xnatx.plugins.transfer.model.TransferRequest;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferService;
import org.nrg.xnatx.plugins.transfer.service.ManifestService;
import org.nrg.xnatx.plugins.transfer.service.ManifestValidationException;
import org.nrg.xnatx.plugins.transfer.service.PreflightResolver;
import org.nrg.xnatx.plugins.transfer.service.ScriptCompiler;
import org.nrg.xnatx.plugins.transfer.service.ScriptValidationException;
import org.nrg.xnatx.plugins.transfer.service.TransferCapabilitiesService;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang3.StringUtils;
import org.nrg.framework.annotations.XapiRestController;
import org.nrg.xapi.rest.AbstractXapiRestController;
import org.nrg.xapi.rest.XapiRequestMapping;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.security.helpers.Permissions;
import org.nrg.xdat.security.services.RoleHolder;
import org.nrg.xdat.security.services.UserManagementServiceI;
import org.nrg.xft.security.UserI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import io.swagger.annotations.Api;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE;
import static org.springframework.web.bind.annotation.RequestMethod.GET;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

@Slf4j
@Api("Batch Transfer Api")
@RequestMapping(value = "/transfer")
@XapiRestController
public class BatchTransferApi extends AbstractXapiRestController {

    private final BatchTransferService batchTransferService;
    private final TransferCapabilitiesService capabilitiesService;
    private final ScriptCompiler scriptCompiler;
    private final ManifestService manifestService;
    private final PreflightResolver preflightResolver;

    protected BatchTransferApi(UserManagementServiceI userManagementService, RoleHolder roleHolder,
                               BatchTransferService batchTransferService, TransferCapabilitiesService capabilitiesService,
                               ScriptCompiler scriptCompiler, ManifestService manifestService,
                               PreflightResolver preflightResolver) {
        super(userManagementService, roleHolder);
        this.batchTransferService = batchTransferService;
        this.capabilitiesService = capabilitiesService;
        this.scriptCompiler = scriptCompiler;
        this.manifestService = manifestService;
        this.preflightResolver = preflightResolver;
    }

    @ApiOperation(value = "Submits an async batch transfer request")
    @ApiResponses({ @ApiResponse(code = 200, message = "Request submitted"),
                    @ApiResponse(code = 400, message = "Invalid request (e.g. the same item twice, two Reimport items "
                            + "routed to one session label, an invalid routing label, or a custom anon_script on a "
                            + "non-Reimport item)"),
                    @ApiResponse(code = 403, message = "No read access to source_project, or no edit access to a "
                            + "destination project required by a custom anon_script"),
                    @ApiResponse(code = 409, message = "The tracking_id already has a run, or this XNAT build does "
                            + "not support per-import anonymization"),
                    @ApiResponse(code = 500, message = "An unexpected error occurred.")})
    @XapiRequestMapping(value = {""}, consumes = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<String> submit(@RequestBody BatchTransfer batchTransferRequest) {
        final List<TransferRequest> requests = batchTransferRequest.getRequests();
        if (requests == null || requests.isEmpty()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }

        final String batchError = checkBatch(requests);
        if (batchError != null) {
            return new ResponseEntity<>(batchError, HttpStatus.BAD_REQUEST);
        }

        final UserI user = getSessionUser();

        final String sourceProject = batchTransferRequest.getSourceProject();
        if (StringUtils.isNotBlank(sourceProject) && !canReadProject(sourceProject, user)) {
            return new ResponseEntity<>("You do not have read access to project " + sourceProject + ".",
                    HttpStatus.FORBIDDEN);
        }

        final ResponseEntity<String> guardrail = checkAnonGuardrails(batchTransferRequest.getAnonScript(), requests, user);
        if (guardrail != null) {
            return guardrail;
        }

        try {
            batchTransferService.submitTransferRequest(batchTransferRequest, user);
        } catch (BatchTransferRunExistsException e) {
            return new ResponseEntity<>(e.getMessage(), HttpStatus.CONFLICT);
        } catch (RuntimeException e) {
            // Two submits with one tracking id at the same moment: the run's unique key rejects the second.
            if (isUniqueViolation(e)) {
                return new ResponseEntity<>(new BatchTransferRunExistsException(batchTransferRequest.getTrackingId())
                        .getMessage(), HttpStatus.CONFLICT);
            }
            throw e;
        }
        return new ResponseEntity<>(HttpStatus.OK);
    }

    private static boolean isUniqueViolation(final Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException && "23505".equals(((SQLException) cause).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks on the batch as a whole that need no XNAT lookup: every item has an id, no item listed twice, every
     * explicit Reimport routing label a valid XNAT label, and no two Reimport items routed to the same explicit
     * session label. Routing labels apply only to Reimport, so Share and Clone items aren't label-checked. Returns
     * the 400 message naming the items, or {@code null} when the batch passes.
     */
    static String checkBatch(final List<TransferRequest> requests) {
        final Set<String> seen = new HashSet<>();
        final Set<String> repeated = new LinkedHashSet<>();
        int missing = 0;
        for (final TransferRequest request : requests) {
            if (StringUtils.isBlank(request.getId())) {
                missing++;
            } else if (!seen.add(request.getId())) {
                repeated.add(request.getId());
            }
        }
        if (missing > 0) {
            return "Every item needs an id; " + missing + (missing == 1 ? " item has" : " items have") + " none.";
        }
        if (!repeated.isEmpty()) {
            return "The request lists the same item more than once: " + listOf(repeated) + ".";
        }

        final List<String> invalid = new ArrayList<>();
        final Map<String, List<String>> bySessionLabel = new LinkedHashMap<>();
        for (final TransferRequest request : requests) {
            if (request.getMode() != TransferMode.REIMPORT) {
                continue;
            }
            final String subjectLabel = request.getDestinationSubjectLabel();
            final String sessionLabel = request.getDestinationSessionLabel();
            if (StringUtils.isNotBlank(subjectLabel) && !ManifestService.isValidLabel(subjectLabel)) {
                invalid.add(request.getId() + " destination_subject_label '" + subjectLabel + "'");
            }
            if (StringUtils.isNotBlank(sessionLabel)) {
                if (!ManifestService.isValidLabel(sessionLabel)) {
                    invalid.add(request.getId() + " destination_session_label '" + sessionLabel + "'");
                }
                bySessionLabel.computeIfAbsent(sessionLabel, k -> new ArrayList<>()).add(request.getId());
            }
        }
        if (!invalid.isEmpty()) {
            return "Invalid destination labels (letters, digits, _ and -, starting with a letter or digit): "
                    + listOf(invalid) + ".";
        }

        final List<String> shared = new ArrayList<>();
        for (final Map.Entry<String, List<String>> entry : bySessionLabel.entrySet()) {
            if (entry.getValue().size() > 1) {
                shared.add("'" + entry.getKey() + "' (" + String.join(", ", entry.getValue()) + ")");
            }
        }
        if (!shared.isEmpty()) {
            return "Reimport items are routed to the same destination_session_label: " + listOf(shared) + ".";
        }
        return null;
    }

    /** "A, B, C", or the first ten and "and N more". */
    private static String listOf(final Iterable<String> values) {
        final List<String> all = new ArrayList<>();
        values.forEach(all::add);
        final int shown = Math.min(all.size(), 10);
        final String head = String.join(", ", all.subList(0, shown));
        return all.size() > shown ? head + " and " + (all.size() - shown) + " more" : head;
    }

    /**
     * Enforces the custom-anon-script guardrails at submit: feature support (409), Reimport-only (400), edit
     * access on every destination (403), then the {@link ScriptCompiler} size/parse/restriction/binding/charset
     * checks. Returns {@code null} when the batch is clear to submit (including when there is no script), or the
     * error response to return otherwise.
     */
    private ResponseEntity<String> checkAnonGuardrails(final String anonScript, final List<TransferRequest> requests,
                                                       final UserI user) {
        if (StringUtils.isBlank(anonScript)) {
            return null;
        }
        if (!capabilitiesService.isPerImportAnonSupported()) {
            return new ResponseEntity<>(
                    "This XNAT build does not support per-import anonymization (the Anon-Script importer "
                            + "parameter is unavailable). Omit anon_script or upgrade XNAT.",
                    HttpStatus.CONFLICT);
        }
        for (final TransferRequest request : requests) {
            if (request.getMode() != TransferMode.REIMPORT) {
                return new ResponseEntity<>(
                        "A custom anon_script is only valid when every request is a Reimport.",
                        HttpStatus.BAD_REQUEST);
            }
        }
        final Set<String> destinations = new HashSet<>();
        for (final TransferRequest request : requests) {
            destinations.add(request.getDestinationProject());
        }
        for (final String destination : destinations) {
            try {
                if (!Permissions.canEditProject(user, destination)) {
                    return new ResponseEntity<>(
                            "You must have edit access to project " + destination
                                    + " to apply a custom anonymization script.",
                            HttpStatus.FORBIDDEN);
                }
            } catch (Exception e) {
                log.error("Could not verify edit permission on project {}", destination, e);
                return new ResponseEntity<>(
                        "Could not verify edit permission on project " + destination + ".",
                        HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        // Size, parse, thread-safety restriction, ${csv.*} binding, and value-charset checks. Throws
        // with the HTTP status the response should carry.
        try {
            scriptCompiler.validateBatch(anonScript, requests);
        } catch (ScriptValidationException e) {
            return new ResponseEntity<>(e.getMessage(), e.getStatus());
        }
        return null;
    }

    @ApiOperation(value = "Reports per-import anonymization capability and limits for this XNAT build")
    @ApiResponses({ @ApiResponse(code = 200, message = "Capabilities returned") })
    @XapiRequestMapping(value = {"capabilities"}, produces = APPLICATION_JSON_VALUE, method = GET)
    @ResponseBody
    public ResponseEntity<TransferCapabilities> capabilities() {
        return new ResponseEntity<>(capabilitiesService.getCapabilities(), HttpStatus.OK);
    }

    // ---- Script validation (advisory) ------------------------------------------------------------------

    @ApiOperation(value = "Validates a custom DicomEdit anon script (parse + ${csv.*} binding + thread-safety "
            + "restriction); advisory — validity is in the body, not the status")
    @ApiResponses({ @ApiResponse(code = 200, message = "Verdict returned (valid true/false)"),
                    @ApiResponse(code = 400, message = "Malformed request (missing anon_script)") })
    @XapiRequestMapping(value = {"validate/script"}, consumes = APPLICATION_JSON_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> validateScript(@RequestBody final ScriptValidationRequest request) {
        if (request == null || StringUtils.isBlank(request.getAnonScript())) {
            return new ResponseEntity<>("anon_script is required.", HttpStatus.BAD_REQUEST);
        }
        return new ResponseEntity<>(
                scriptCompiler.validate(request.getAnonScript(), request.getManifestColumns()), HttpStatus.OK);
    }

    // ---- Manifest: parse + light preflight (validate) --------------------------------------------------

    @ApiOperation(value = "Parses a manifest CSV and resolves each row to source sessions for Reimport (no data "
            + "touched); mode is accepted and ignored")
    @ApiResponses({ @ApiResponse(code = 200, message = "Parsed result (including required-column/not-found detail)"),
                    @ApiResponse(code = 400, message = "Unparseable CSV or missing source_project"),
                    @ApiResponse(code = 403, message = "No read access to the source project"),
                    @ApiResponse(code = 413, message = "Manifest exceeds the row cap")})
    @XapiRequestMapping(value = {"validate/manifest"}, consumes = APPLICATION_JSON_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> validateManifest(@RequestBody final ManifestValidationRequest request) {
        return doValidateManifest(request.getSourceProject(), request.getManifestCsv(), request.getAnonScript(),
                request.isFileCount(), getSessionUser());
    }

    @ApiOperation(value = "Parses an uploaded manifest CSV and resolves each row to source sessions for Reimport; "
            + "mode is accepted and ignored")
    @XapiRequestMapping(value = {"validate/manifest"}, consumes = MULTIPART_FORM_DATA_VALUE,
            produces = APPLICATION_JSON_VALUE, method = POST)
    @ResponseBody
    public ResponseEntity<?> validateManifestUpload(
            @RequestParam("manifest") final MultipartFile manifest,
            @RequestParam("source_project") final String sourceProject,
            @RequestParam(value = "mode", required = false) final String mode,
            @RequestParam(value = "anon_script", required = false) final String anonScript,
            @RequestParam(value = "file_count", required = false) final Boolean fileCount) {
        if (resolveMode(mode) == null) {
            return new ResponseEntity<>("Invalid mode: " + mode, HttpStatus.BAD_REQUEST);
        }
        final String csv;
        try {
            csv = readCsv(manifest);
        } catch (IOException e) {
            return new ResponseEntity<>("Could not read the uploaded manifest: " + e.getMessage(),
                    HttpStatus.BAD_REQUEST);
        }
        return doValidateManifest(sourceProject, csv, anonScript, Boolean.TRUE.equals(fileCount), getSessionUser());
    }

    private ResponseEntity<?> doValidateManifest(final String sourceProject, final String csv,
                                                 final String anonScript, final boolean includeFileCount,
                                                 final UserI user) {
        if (StringUtils.isBlank(sourceProject)) {
            return new ResponseEntity<>("source_project is required.", HttpStatus.BAD_REQUEST);
        }
        if (!canReadProject(sourceProject, user)) {
            return new ResponseEntity<>("You do not have read access to project " + sourceProject + ".",
                    HttpStatus.FORBIDDEN);
        }

        final ManifestValidationResult result;
        try {
            result = manifestService.parse(csv, anonScript);
        } catch (ManifestValidationException e) {
            return new ResponseEntity<>(e.getMessage(), e.getStatus());
        }

        if (result.isRequiredPresent() && !result.getRows().isEmpty()) {
            result.setRows(preflightResolver.resolveAll(sourceProject, result.getRows(), user, includeFileCount));
            result.setSummary(summarize(result.getRows()));
        }
        return new ResponseEntity<>(result, HttpStatus.OK);
    }

    /** Tally resolved entries: matched sessions, and rows by each non-matched status. */
    private static ManifestSummary summarize(final List<ManifestRow> rows) {
        final ManifestSummary summary = new ManifestSummary();
        for (final ManifestRow row : rows) {
            final String status = row.getStatus();
            if (ManifestRow.STATUS_MATCHED.equals(status)) {
                summary.setMatched(summary.getMatched() + 1);
            } else if (ManifestRow.STATUS_NO_SESSIONS.equals(status)) {
                summary.setNoSessions(summary.getNoSessions() + 1);
            } else if (ManifestRow.STATUS_DUPLICATE_SESSION.equals(status)) {
                summary.setDuplicateSession(summary.getDuplicateSession() + 1);
            } else {
                summary.setNotFound(summary.getNotFound() + 1);
            }
        }
        return summary;
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /**
     * True when the user can read (or the project is otherwise resolvable to) {@code project}. Protected seam
     * (like the service-layer {@code runImporter}/{@code destinationAutoArchives}) so tests can stub the read
     * check without mocking the {@code XnatProjectdata} static.
     */
    protected boolean canReadProject(final String project, final UserI user) {
        try {
            return XnatProjectdata.getProjectByIDorAlias(project, user, false) != null;
        } catch (Exception e) {
            log.error("Could not verify read access on project {}", project, e);
            return false;
        }
    }

    /** Blank → the default Reimport; a known value → its mode; anything else → null (an invalid mode). */
    private static TransferMode resolveMode(final String mode) {
        if (StringUtils.isBlank(mode)) {
            return TransferMode.REIMPORT;
        }
        for (final TransferMode candidate : TransferMode.values()) {
            if (candidate.getValue().equalsIgnoreCase(mode)) {
                return candidate;
            }
        }
        return null;
    }

    /** Read an uploaded manifest as UTF-8 text, stripping a leading byte-order mark. */
    private static String readCsv(final MultipartFile file) throws IOException {
        try (InputStream in = new BOMInputStream(file.getInputStream())) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }
}
