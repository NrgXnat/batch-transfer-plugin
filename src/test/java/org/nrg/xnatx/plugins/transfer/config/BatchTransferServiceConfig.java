package org.nrg.xnatx.plugins.transfer.config;

import org.nrg.xnatx.plugins.transfer.service.impl.BatchTransferServiceImpl;
import org.mockito.Mockito;
import org.nrg.framework.services.ContextService;
import org.nrg.framework.services.NrgEventService;
import org.nrg.xnat.helpers.merge.AnonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.concurrent.ExecutorService;

@Configuration
@Import({MockConfig.class})
public class BatchTransferServiceConfig {

    /**
     * Returns a Mockito spy of {@link BatchTransferServiceImpl} so tests can stub
     * the protected {@code runImporter} seam via {@code doReturn(...).when(service)}.
     * All other methods call through to the real implementation.
     */
    @Bean
    public BatchTransferServiceImpl batchTransferService(final NrgEventService nrgEventService,
                                                   final ExecutorService executorService,
                                                   final AnonUtils anonUtils,
                                                   final ContextService contextService) {
        return Mockito.spy(new BatchTransferServiceImpl(nrgEventService, executorService, anonUtils, contextService));
    }
}
