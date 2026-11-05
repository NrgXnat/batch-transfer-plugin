package org.nrg.xnatx.plugins.transfer.jms.preferences;

import org.junit.Before;
import org.junit.Test;
import org.nrg.framework.constants.Scope;
import org.nrg.framework.services.NrgEventServiceI;
import org.nrg.prefs.services.NrgPreferenceService;

import java.util.Properties;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests the stored-default upgrade in {@link BatchTransferPrefsBean#postProcessPreferences()} against a mocked
 * preference store.
 */
public class BatchTransferPrefsBeanTest {

    private static final String TOOL_ID = "batch-transfer-jms-queue";
    private static final String VERSION_PATTERN = "allowedAnonVersionPattern";

    private NrgPreferenceService preferenceService;
    private BatchTransferPrefsBean prefs;

    @Before
    public void setUp() {
        preferenceService = mock(NrgPreferenceService.class);
        when(preferenceService.getToolProperties(eq(TOOL_ID), anyList())).thenReturn(new Properties());
        prefs = new BatchTransferPrefsBean(preferenceService, mock(NrgEventServiceI.class), null, null);
    }

    @Test
    public void replacesStoredLegacyVersionPattern() throws Exception {
        when(preferenceService.getPreferenceValue(TOOL_ID, VERSION_PATTERN))
                .thenReturn(BatchTransferPrefsBean.LEGACY_ALLOWED_ANON_VERSION_PATTERN);

        prefs.postProcessPreferences();

        verify(preferenceService).setPreferenceValue(eq(TOOL_ID), eq(VERSION_PATTERN), any(Scope.class), any(),
                eq(BatchTransferPrefsBean.ALLOWED_ANON_VERSION_PATTERN_DFLT));
    }

    @Test
    public void keepsAnyOtherStoredVersionPattern() throws Exception {
        when(preferenceService.getPreferenceValue(TOOL_ID, VERSION_PATTERN)).thenReturn("^6\\.6$");

        prefs.postProcessPreferences();

        verify(preferenceService, never()).setPreferenceValue(anyString(), anyString(), any(Scope.class), any(), anyString());
        verify(preferenceService, never()).setPreferenceValue(anyString(), anyString(), anyString());
    }
}
