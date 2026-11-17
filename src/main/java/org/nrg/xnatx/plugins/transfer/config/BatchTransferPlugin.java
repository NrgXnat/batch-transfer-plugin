package org.nrg.xnatx.plugins.transfer.config;
import org.nrg.framework.annotations.XnatPlugin;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan("org.nrg.xnatx.plugins.transfer")
@XnatPlugin(value = "batchTransferPlugin",
        name = "Batch Transfer Plugin",
        description = "Batch Transfer Plugin",
        entityPackages = "org.nrg.xnatx.plugins.transfer.model.entity",
        logConfigurationFile = "META-INF/resources/batch_transfer_logback.xml")
public class BatchTransferPlugin { }
