package org.nrg.xnatx.plugins.transfer.plugin;
import org.nrg.framework.annotations.XnatPlugin;
import org.springframework.context.annotation.ComponentScan;

@ComponentScan({
        "org.nrg.xnatx.plugins.transfer.api",
        "org.nrg.xnatx.plugins.transfer.service",
        "org.nrg.xnatx.plugins.transfer.event",
})
@XnatPlugin(value = "batchTransferPlugin",
        name = "Batch Transfer Plugin",
        description = "Batch Transfer Plugin",
        logConfigurationFile = "META-INF/resources/batch_transfer_logback.xml")
public class BatchTransferPlugin { }
