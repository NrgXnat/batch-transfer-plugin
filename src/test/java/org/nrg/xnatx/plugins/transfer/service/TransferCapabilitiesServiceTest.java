package org.nrg.xnatx.plugins.transfer.service;

import org.junit.Test;
import org.nrg.xnatx.plugins.transfer.model.TransferCapabilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * The capabilities service reports the static limits + the configured manifest cap and reflects the
 * (reflective) per-import anon probe. The probe itself is stubbed so the test doesn't depend on what xnat-web
 * is on the classpath.
 */
public class TransferCapabilitiesServiceTest {

    private static BatchTransferPolicy policy(final int maxRows) {
        final BatchTransferPolicy policy = mock(BatchTransferPolicy.class);
        when(policy.getManifestMaxRows()).thenReturn(maxRows);
        return policy;
    }

    @Test
    public void reportsConstantsAndProbeWhenSupported() {
        final TransferCapabilitiesService svc = spy(new TransferCapabilitiesService(policy(5000)));
        doReturn(true).when(svc).detectPerImportAnonSupported();

        final TransferCapabilities cap = svc.getCapabilities();
        assertTrue(cap.isPerImportAnon());
        assertEquals("DicomEdit 6", cap.getAnonScriptDialect());
        assertEquals("${csv.<column>}", cap.getPlaceholderSyntax());
        assertEquals(Integer.valueOf(5000), cap.getManifestMaxRows());
    }

    @Test
    public void reportsUnsupportedWhenProbeFails() {
        final TransferCapabilitiesService svc = spy(new TransferCapabilitiesService(policy(5000)));
        doReturn(false).when(svc).detectPerImportAnonSupported();

        assertFalse(svc.getCapabilities().isPerImportAnon());
        assertFalse(svc.isPerImportAnonSupported());
    }

    @Test
    public void probeNeverThrowsAndIsStable() {
        final TransferCapabilitiesService svc = new TransferCapabilitiesService(policy(5000));
        final boolean first = svc.isPerImportAnonSupported();   // must not throw against the test classpath
        assertEquals(first, svc.isPerImportAnonSupported());    // cached / stable across calls
    }
}
