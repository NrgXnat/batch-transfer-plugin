package org.nrg.xnatx.plugins.transfer.config;

import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xdat.om.XnatImageassessordata;
import org.nrg.xdat.om.XnatImagesessiondata;
import org.nrg.xdat.om.XnatProjectdata;
import org.nrg.xdat.om.XnatSubjectdata;
import org.nrg.xft.security.UserI;
import org.nrg.xnat.DicomObjectIdentifier;
import org.nrg.xnat.helpers.merge.AnonUtils;
import org.nrg.xnatx.plugins.transfer.jms.tasks.BatchTransferMonitor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Central test configuration: provides Mockito-mock {@code @Bean}s for every
 * collaborator a BatchTransferServiceImpl test might need. Tests re-stub behaviour
 * in {@code @Before} and scope static mocks per-test via {@code MockedStatic}.
 */
@Configuration
public class MockConfig {

    @Bean
    public NrgEventService nrgEventService() {
        return mock(NrgEventService.class);
    }

    @Bean
    public ExecutorService executorService() {
        return mock(ExecutorService.class);
    }

    @Bean
    public AnonUtils anonUtils() {
        return mock(AnonUtils.class);
    }

    @Bean
    public DicomObjectIdentifier dicomObjectIdentifier() {
        return mock(DicomObjectIdentifier.class);
    }

    /**
     * BatchTransferServiceImpl's constructor eagerly resolves the
     * "dicomObjectIdentifier" bean, so the stub must be in place before
     * Spring instantiates the SUT.
     */
    @Bean
    public ContextService contextService(final DicomObjectIdentifier dicomObjectIdentifier) {
        final ContextService ctx = mock(ContextService.class);
        when(ctx.getBean("dicomObjectIdentifier", DicomObjectIdentifier.class))
                .thenReturn(dicomObjectIdentifier);
        return ctx;
    }

    @Bean
    public UserI user() {
        return mock(UserI.class);
    }

    @Bean
    public XnatProjectdata destinationProjectData() {
        return mock(XnatProjectdata.class);
    }

    @Bean
    public XnatImagesessiondata imageSession() {
        return mock(XnatImagesessiondata.class);
    }

    @Bean
    public XnatImageassessordata imageAssessor() {
        return mock(XnatImageassessordata.class);
    }

    @Bean
    public XnatSubjectdata subject() {
        return mock(XnatSubjectdata.class);
    }

    @Bean
    public BatchTransferMonitor batchTransferMonitor() {
        return mock(BatchTransferMonitor.class);
    }
}
