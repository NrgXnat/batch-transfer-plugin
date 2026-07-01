package org.nrg.xnatx.plugins.transfer.service;

/**
 * The admin-tunable limits and restriction settings the {@link ScriptCompiler} enforces, separated from the
 * concrete preference bean so the compiler depends on a small abstraction (and is unit-testable without
 * initializing the XDAT preferences hierarchy). Implemented by {@code BatchTransferQueuePrefsBean}.
 */
public interface AnonScriptPolicy {

    /** Maximum size (bytes, UTF-8) of a custom anonymization script. */
    Integer getMaxAnonScriptBytes();

    /** Allowed character set (a regex character-class body) for {@code ${csv.*}} substitution values. */
    String getAnonValueCharset();

    /** Comma/space-separated DicomEdit verbs forbidden in a custom script. */
    String getRestrictedAnonVerbs();

    /** Regex a custom script's declared {@code version "x.y"} must match. */
    String getAllowedAnonVersionPattern();
}
