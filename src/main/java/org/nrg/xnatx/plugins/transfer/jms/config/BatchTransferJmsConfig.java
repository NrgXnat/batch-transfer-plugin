package org.nrg.xnatx.plugins.transfer.jms.config;

import org.apache.activemq.command.ActiveMQQueue;
import org.nrg.xnatx.plugins.transfer.jms.errors.BatchTransferJmsErrorHandler;
import org.nrg.xnatx.plugins.transfer.jms.preferences.BatchTransferQueuePrefsBean;
import org.nrg.xnatx.plugins.transfer.jms.requests.CloneSubjectRequest;
import org.nrg.xnatx.plugins.transfer.jms.requests.TransferItemRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.annotation.EnableJms;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

import javax.jms.ConnectionFactory;
import javax.jms.Destination;

/**
 * JMS wiring for parallel batch transfers, modeled on container-service's {@code ContainersConfig}.
 * Reuses xnat-web's embedded broker / connection factory / {@code JmsTemplate} (defined in
 * {@code MqConfig}) — this only adds the queue and the listener-container factory.
 *
 * <ul>
 *   <li>{@code @EnableJms} turns on {@code @JmsListener} processing for the plugin.</li>
 *   <li>Consumer concurrency comes from {@link BatchTransferQueuePrefsBean} (admin-tunable).</li>
 * </ul>
 */
@Configuration
@EnableJms
public class BatchTransferJmsConfig {

    public static final String REIMPORT_LISTENER_FACTORY = "batchTransferReimportListenerFactory";
    public static final String REIMPORT_LISTENER_ID      = "batchTransferReimportListener";
    public static final String CLONE_LISTENER_FACTORY    = "batchTransferCloneListenerFactory";
    public static final String CLONE_LISTENER_ID         = "batchTransferCloneListener";

    /** Scale consumers down after ~5 minutes idle (300 receives * 1s timeout). */
    private static final int  IDLE_RECEIVES_PER_TASK_LIMIT = 300;
    private static final long RECEIVE_TIMEOUT_MS           = 1000L;

    @Bean(name = TransferItemRequest.DESTINATION)
    public Destination transferItemRequest() {
        return new ActiveMQQueue(TransferItemRequest.DESTINATION);
    }

    @Bean(name = REIMPORT_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory batchTransferReimportListenerFactory(
            final BatchTransferQueuePrefsBean prefs,
            @Qualifier("springConnectionFactory") final ConnectionFactory connectionFactory) {
        final DefaultJmsListenerContainerFactory factory = defaultFactory(connectionFactory);
        factory.setConcurrency(prefs.getReimportConcurrencyMin() + "-" + prefs.getReimportConcurrencyMax());
        return factory;
    }

    @Bean(name = CloneSubjectRequest.DESTINATION)
    public Destination cloneSubjectRequest() {
        return new ActiveMQQueue(CloneSubjectRequest.DESTINATION);
    }

    @Bean(name = CLONE_LISTENER_FACTORY)
    public DefaultJmsListenerContainerFactory batchTransferCloneListenerFactory(
            final BatchTransferQueuePrefsBean prefs,
            @Qualifier("springConnectionFactory") final ConnectionFactory connectionFactory) {
        final DefaultJmsListenerContainerFactory factory = defaultFactory(connectionFactory);
        factory.setConcurrency(prefs.getCloneConcurrencyMin() + "-" + prefs.getCloneConcurrencyMax());
        return factory;
    }

    private DefaultJmsListenerContainerFactory defaultFactory(final ConnectionFactory connectionFactory) {
        final DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory() {
            @Override
            protected DefaultMessageListenerContainer createContainerInstance() {
                final DefaultMessageListenerContainer container = super.createContainerInstance();
                container.setIdleReceivesPerTaskLimit(IDLE_RECEIVES_PER_TASK_LIMIT);
                container.setReceiveTimeout(RECEIVE_TIMEOUT_MS);
                return container;
            }
        };
        factory.setConnectionFactory(connectionFactory);
        factory.setErrorHandler(new BatchTransferJmsErrorHandler());
        return factory;
    }
}
