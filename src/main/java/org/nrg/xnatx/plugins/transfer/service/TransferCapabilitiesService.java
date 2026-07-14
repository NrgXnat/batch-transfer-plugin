package org.nrg.xnatx.plugins.transfer.service;

import lombok.extern.slf4j.Slf4j;
import org.nrg.xnatx.plugins.transfer.model.TransferCapabilities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Reports whether the deployed xnat-web build supports per-import anonymization — i.e. whether
 * {@code GradualDicomImporter} carries the {@code Anon-Script} importer param — plus the static limits the
 * UI and request validation share.
 *
 * <p>The check is <b>reflective</b> so the plugin compiles and runs against builds with or without the
 * change: the param constant is never referenced directly. The result is cached (the deployed build does
 * not change at runtime).
 */
@Slf4j
@Service
public class TransferCapabilitiesService {

    static final String GRADUAL_DICOM_IMPORTER  = "org.nrg.xnat.archive.GradualDicomImporter";
    static final String ANON_SCRIPT_PARAM_FIELD = "ANON_SCRIPT_PARAM";

    public static final String ANON_SCRIPT_DIALECT = "DicomEdit 6";
    public static final String PLACEHOLDER_SYNTAX  = "${csv.<column>}";

    private final BatchTransferPolicy policy;

    /** Lazily computed once; see {@link #detectPerImportAnonSupported()}. */
    private Boolean perImportAnonSupported;

    @Autowired
    public TransferCapabilitiesService(final BatchTransferPolicy policy) {
        this.policy = policy;
    }

    public TransferCapabilities getCapabilities() {
        return new TransferCapabilities(isPerImportAnonSupported(), ANON_SCRIPT_DIALECT, PLACEHOLDER_SYNTAX,
                policy.getManifestMaxRows());
    }

    public synchronized boolean isPerImportAnonSupported() {
        if (perImportAnonSupported == null) {
            perImportAnonSupported = detectPerImportAnonSupported();
        }
        return perImportAnonSupported;
    }

    /**
     * True when the deployed {@code GradualDicomImporter} exposes the {@code ANON_SCRIPT_PARAM} field.
     * Loads the class <b>without initializing it</b> (its static initializers need XDAT/Spring wiring that
     * may be absent here) and only inspects field presence — never reads the value — so no class
     * initialization is forced. Overridable for testing.
     */
    protected boolean detectPerImportAnonSupported() {
        try {
            final Class<?> importer = Class.forName(GRADUAL_DICOM_IMPORTER, false, getClass().getClassLoader());
            importer.getField(ANON_SCRIPT_PARAM_FIELD);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            log.debug("Per-import anonymization unavailable: {}#{} not found ({})",
                    GRADUAL_DICOM_IMPORTER, ANON_SCRIPT_PARAM_FIELD, e.toString());
            return false;
        }
    }
}
