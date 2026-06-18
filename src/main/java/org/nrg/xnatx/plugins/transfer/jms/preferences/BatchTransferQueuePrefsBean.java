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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Admin-tunable concurrency for the Batch Transfer JMS queues. Surfaces in the site-admin
 * preferences UI (the {@link NrgPreferenceBean} annotation is meta-{@code @Component}, so the
 * plugin's component scan registers it). The JMS listener-container factories in
 * {@code BatchTransferJmsConfig} read these to set each queue's {@code min-max} consumer concurrency.
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
public class BatchTransferQueuePrefsBean extends EventTriggeringAbstractPreferenceBean {

    public static final String REIMPORT_CONCURRENCY_MIN_DFLT = "4";
    public static final String REIMPORT_CONCURRENCY_MAX_DFLT = "8";
    public static final String CLONE_CONCURRENCY_MIN_DFLT     = "1";
    public static final String CLONE_CONCURRENCY_MAX_DFLT     = "2";

    private static final String REIMPORT_MIN = "reimportConcurrencyMin";
    private static final String REIMPORT_MAX = "reimportConcurrencyMax";
    private static final String CLONE_MIN    = "cloneConcurrencyMin";
    private static final String CLONE_MAX    = "cloneConcurrencyMax";

    @Autowired
    public BatchTransferQueuePrefsBean(final NrgPreferenceService preferenceService,
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
}
