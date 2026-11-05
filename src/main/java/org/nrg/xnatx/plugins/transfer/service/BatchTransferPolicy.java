package org.nrg.xnatx.plugins.transfer.service;

/**
 * The admin-tunable limits + restriction settings the batch-transfer services consume — the anon-script
 * bounds for {@link ScriptCompiler} and the manifest row cap for {@link ManifestService} /
 * {@link TransferCapabilitiesService}. Separated from the concrete preference bean so those services depend on
 * a small abstraction and stay unit-testable without initializing the XDAT preferences hierarchy (whose
 * supertype init trips a test-classpath slf4j/log4j conflict). Implemented by {@code BatchTransferPrefsBean}.
 */
public interface BatchTransferPolicy {

    /** Maximum size (bytes, UTF-8) of a custom anonymization script. */
    Integer getMaxAnonScriptBytes();

    /** Allowed character set (a regex character-class body) for {@code ${csv.*}} substitution values. */
    String getAnonValueCharset();

    /** Comma/space-separated DicomEdit verbs forbidden in a custom script. */
    String getRestrictedAnonVerbs();

    /** Regex a custom script's declared {@code version "x.y"} must match. */
    String getAllowedAnonVersionPattern();

    /** Maximum number of data rows a manifest may carry for the synchronous preflight. */
    Integer getManifestMaxRows();
}
