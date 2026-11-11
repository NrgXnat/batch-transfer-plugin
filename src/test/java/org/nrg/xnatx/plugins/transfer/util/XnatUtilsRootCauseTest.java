package org.nrg.xnatx.plugins.transfer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** The message a failed item reports: the deepest cause that has one. */
public class XnatUtilsRootCauseTest {

    @Test
    public void aRootWithoutAMessageReportsTheDeepestMessageAboveIt() {
        // The shape of an XNAT archive conflict: the real reason sits just above a message-less root.
        final Exception failure = new Exception("Reimported S1 to project P Failed",
                new RuntimeException("unable to archive for the Prearchive session",
                        new IllegalStateException("Session already exists with matching files.", new Exception())));
        assertEquals("Session already exists with matching files.", XnatUtils.rootCauseMessage(failure));
    }

    @Test
    public void theRootsOwnMessageWins() {
        assertEquals("disk full", XnatUtils.rootCauseMessage(new Exception("wrapper", new Exception("disk full"))));
    }

    @Test
    public void withNoMessageAnywhereTheRootsClassNameIsReported() {
        assertEquals("IllegalStateException",
                XnatUtils.rootCauseMessage(new Exception(null, new IllegalStateException())));
    }

    @Test
    public void aCyclicChainEnds() {
        final Exception first = new Exception("first");
        final Exception second = new Exception("second", first);
        first.initCause(second);
        assertEquals("second", XnatUtils.rootCauseMessage(first));
    }
}
