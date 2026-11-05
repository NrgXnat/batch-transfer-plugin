package org.nrg.xnatx.plugins.transfer.jms.preferences;

import lombok.extern.slf4j.Slf4j;
import org.nrg.framework.configuration.ConfigPaths;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.framework.utilities.OrderedProperties;
import org.nrg.prefs.annotations.NrgPreference;
import org.nrg.prefs.annotations.NrgPreferenceBean;
import org.nrg.prefs.exceptions.InvalidPreferenceName;
import org.nrg.prefs.services.NrgPreferenceService;
import org.nrg.xdat.preferences.EventTriggeringAbstractPreferenceBean;
import org.nrg.xnatx.plugins.transfer.service.BatchTransferPolicy;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Admin-tunable settings for the Batch Transfer plugin: JMS consumer concurrency plus the per-import
 * anonymization limits/restriction and the manifest row cap (the latter surfaced through
 * {@link BatchTransferPolicy}). Surfaces in the site-admin preferences UI (the {@link NrgPreferenceBean}
 * annotation is meta-{@code @Component}, so the plugin's component scan registers it). The JMS
 * listener-container factories in {@code BatchTransferJmsConfig} read the concurrency values to set each
 * queue's {@code min-max}; {@code ScriptCompiler}, {@code ManifestService}, and
 * {@code TransferCapabilitiesService} read the rest.
 *
 * <p>Defaults are deliberately conservative: each Reimport consumer spawns a streaming-zip producer
 * thread plus a {@code DicomZipImporter}, and the prearchive Rebuild each one queues is itself
 * parallel (up to {@code prearchiveOperationJmsListenerMaxConcurrency}, default 40/node) — so plugin
 * concurrency stacks on the downstream rebuild stage. Set min == max == 1 for a serial kill-switch.
 */
@Slf4j
@NrgPreferenceBean(toolId = "batch-transfer-jms-queue",
        toolName = "Batch Transfer JMS Queue Preferences",
        description = "Consumer concurrency for the Batch Transfer Reimport and Clone JMS queues")
public class BatchTransferPrefsBean extends EventTriggeringAbstractPreferenceBean implements BatchTransferPolicy {

    public static final String REIMPORT_CONCURRENCY_MIN_DFLT = "4";
    public static final String REIMPORT_CONCURRENCY_MAX_DFLT = "8";
    public static final String CLONE_CONCURRENCY_MIN_DFLT     = "1";
    public static final String CLONE_CONCURRENCY_MAX_DFLT     = "2";

    // Per-import anonymization limits + restriction. Declared here so the ScriptCompiler and
    // TransferCapabilitiesService read one source of truth; an admin screen can edit them.
    public static final String MAX_ANON_SCRIPT_BYTES_DFLT       = "262144";              // 256 KB
    public static final String ANON_VALUE_CHARSET_DFLT          = "A-Za-z0-9 ^_.-";      // a regex char-class body
    public static final String RESTRICTED_ANON_VERBS_DFLT       = "mapUID,mapReferencedUIDs,lookup,getURL,alterPixels,newUID";
    public static final String ALLOWED_ANON_VERSION_PATTERN_DFLT = "^6\\.[0-7]$";

    // Manifest preflight limit. The ManifestService and TransferCapabilitiesService read this one source of
    // truth; the synchronous preflight rejects a manifest with more data rows than this.
    public static final String MANIFEST_MAX_ROWS_DFLT           = "5000";

    private static final String REIMPORT_MIN = "reimportConcurrencyMin";
    private static final String REIMPORT_MAX = "reimportConcurrencyMax";
    private static final String CLONE_MIN    = "cloneConcurrencyMin";
    private static final String CLONE_MAX    = "cloneConcurrencyMax";

    private static final String MAX_ANON_SCRIPT_BYTES        = "maxAnonScriptBytes";
    private static final String ANON_VALUE_CHARSET           = "anonValueCharset";
    private static final String RESTRICTED_ANON_VERBS        = "restrictedAnonVerbs";
    private static final String ALLOWED_ANON_VERSION_PATTERN = "allowedAnonVersionPattern";
    private static final String MANIFEST_MAX_ROWS           = "manifestMaxRows";

    @Autowired
    public BatchTransferPrefsBean(final NrgPreferenceService preferenceService,
                                       final NrgEventServiceI eventService,
                                       final ConfigPaths configPaths,
                                       final OrderedProperties initPrefs) {
        super(preferenceService, eventService, configPaths, initPrefs);
    }

    @NrgPreference(defaultValue = REIMPORT_CONCURRENCY_MIN_DFLT)
    public Integer getReimportConcurrencyMin() {
        return getIntegerValue(REIMPORT_MIN);
    }

    public void setReimportConcurrencyMin(final Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, REIMPORT_MIN);
    }

    @NrgPreference(defaultValue = REIMPORT_CONCURRENCY_MAX_DFLT)
    public Integer getReimportConcurrencyMax() {
        return getIntegerValue(REIMPORT_MAX);
    }

    public void setReimportConcurrencyMax(final Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, REIMPORT_MAX);
    }

    @NrgPreference(defaultValue = CLONE_CONCURRENCY_MIN_DFLT)
    public Integer getCloneConcurrencyMin() {
        return getIntegerValue(CLONE_MIN);
    }

    public void setCloneConcurrencyMin(final Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, CLONE_MIN);
    }

    @NrgPreference(defaultValue = CLONE_CONCURRENCY_MAX_DFLT)
    public Integer getCloneConcurrencyMax() {
        return getIntegerValue(CLONE_MAX);
    }

    public void setCloneConcurrencyMax(final Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, CLONE_MAX);
    }

    /** Maximum size (bytes, UTF-8) of a custom {@code anon_script}; submit rejects anything larger. */
    @NrgPreference(defaultValue = MAX_ANON_SCRIPT_BYTES_DFLT)
    public Integer getMaxAnonScriptBytes() {
        return getIntegerValue(MAX_ANON_SCRIPT_BYTES);
    }

    public void setMaxAnonScriptBytes(final Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, MAX_ANON_SCRIPT_BYTES);
    }

    /**
     * Allowed character set (a regex character-class body, e.g. {@code A-Za-z0-9 ^_.-}) for {@code ${csv.*}}
     * substitution values. The {@code ScriptCompiler} sanitizer derives its pattern from this single source.
     */
    @NrgPreference(defaultValue = ANON_VALUE_CHARSET_DFLT)
    public String getAnonValueCharset() {
        return getValue(ANON_VALUE_CHARSET);
    }

    public void setAnonValueCharset(final String value) throws InvalidPreferenceName {
        set(value, ANON_VALUE_CHARSET);
    }

    /**
     * Comma/space-separated DicomEdit verbs forbidden in a custom {@code anon_script} (thread-safety +
     * untrusted-input guardrail). Tunable so a site can add a newly-discovered unsafe verb, or relax once
     * its xnat-web carries the engine fix.
     */
    @NrgPreference(defaultValue = RESTRICTED_ANON_VERBS_DFLT)
    public String getRestrictedAnonVerbs() {
        return getValue(RESTRICTED_ANON_VERBS);
    }

    public void setRestrictedAnonVerbs(final String value) throws InvalidPreferenceName {
        set(value, RESTRICTED_ANON_VERBS);
    }

    /** Regex a custom {@code anon_script}'s declared {@code version "x.y"} must match (default DicomEdit 6.0–6.7). */
    @NrgPreference(defaultValue = ALLOWED_ANON_VERSION_PATTERN_DFLT)
    public String getAllowedAnonVersionPattern() {
        return getValue(ALLOWED_ANON_VERSION_PATTERN);
    }

    public void setAllowedAnonVersionPattern(final String value) throws InvalidPreferenceName {
        set(value, ALLOWED_ANON_VERSION_PATTERN);
    }

    /** Maximum number of data rows a manifest may carry for the synchronous preflight (default 5000). */
    @NrgPreference(defaultValue = MANIFEST_MAX_ROWS_DFLT)
    public Integer getManifestMaxRows() {
        return getIntegerValue(MANIFEST_MAX_ROWS);
    }

    public void setManifestMaxRows(final Integer value) throws InvalidPreferenceName {
        setIntegerValue(value, MANIFEST_MAX_ROWS);
    }
}
