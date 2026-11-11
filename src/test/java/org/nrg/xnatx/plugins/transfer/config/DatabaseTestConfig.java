package org.nrg.xnatx.plugins.transfer.config;

import org.hibernate.SessionFactory;
import org.hibernate.boot.model.naming.ImplicitNamingStrategyLegacyHbmImpl;
import org.nrg.framework.annotations.XnatPlugin;
import org.nrg.framework.orm.hibernate.AggregatedAnnotationSessionFactoryBean;
import org.nrg.framework.orm.hibernate.HibernateEntityPackageList;
import org.nrg.framework.orm.hibernate.PrefixedPhysicalNamingStrategy;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferHistoryDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferItemDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.BatchTransferProgressDAO;
import org.nrg.xnatx.plugins.transfer.jms.tasks.dao.XnatItemQueries;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferItemService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.BatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl.HibernateBatchTransferHistoryService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl.HibernateBatchTransferItemService;
import org.nrg.xnatx.plugins.transfer.jms.tasks.services.impl.HibernateBatchTransferProgressService;
import org.nrg.xnatx.plugins.transfer.plugin.BatchTransferPlugin;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.hibernate5.HibernateTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Properties;

/**
 * Spring context for the database tests: the plugin's Hibernate entities, DAOs and services on a real PostgreSQL in
 * Docker, with the session factory built the way XNAT builds it (the plugin's entity packages, the {@code xhbm_}
 * table prefix and the legacy implicit naming strategy). Trimmed from container-service's test
 * {@code HibernateConfig}. {@code db/xnat-stub.sql} adds the columns of XNAT's own tables that the plugin's SQL
 * reads, so that SQL runs against PostgreSQL without XNAT's schema.
 *
 * <p>Tests that use this context carry the {@code DatabaseTest} category, so only {@code ./gradlew dbTest} runs them.
 * One container serves the whole test JVM. Without Docker the context fails to start with a message saying so, so
 * the tests fail rather than being skipped.
 */
@Configuration
@EnableTransactionManagement
public class DatabaseTestConfig {

    /** The major version the XNAT instances run. */
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    private static PostgreSQLContainer<?> container;

    /** The JVM's container, started on first use. Testcontainers removes it when the JVM exits. */
    private static synchronized PostgreSQLContainer<?> postgres() {
        if (container == null) {
            if (!DockerClientFactory.instance().isDockerAvailable()) {
                throw new IllegalStateException("The database tests need Docker, and Docker isn't available. "
                        + "Start Docker and run ./gradlew dbTest again.");
            }
            final PostgreSQLContainer<?> started = new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE));
            started.start();
            container = started;
        }
        return container;
    }

    @Bean
    public DataSource dataSource() {
        final PostgreSQLContainer<?> postgres = postgres();
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    /** The entity packages XNAT scans for this plugin, read from the plugin's own declaration. */
    @Bean
    public HibernateEntityPackageList batchTransferEntityPackages() {
        return new HibernateEntityPackageList(BatchTransferPlugin.class.getAnnotation(XnatPlugin.class).entityPackages());
    }

    /** Named as in XNAT: the framework's Hibernate services read it by name when they start. */
    @Bean
    public Properties hibernateProperties() {
        final Properties properties = new Properties();
        properties.setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQL10Dialect");
        properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        properties.setProperty("hibernate.cache.use_second_level_cache", "false");
        properties.setProperty("hibernate.cache.use_query_cache", "false");
        return properties;
    }

    @Bean
    public FactoryBean<SessionFactory> sessionFactory(final DataSource dataSource, final Properties hibernateProperties,
                                                      final List<HibernateEntityPackageList> packageLists) {
        final AggregatedAnnotationSessionFactoryBean bean = new AggregatedAnnotationSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setHibernateProperties(hibernateProperties);
        bean.setEntityPackageLists(packageLists);
        bean.setImplicitNamingStrategy(new ImplicitNamingStrategyLegacyHbmImpl());
        bean.setPhysicalNamingStrategy(new PrefixedPhysicalNamingStrategy("xhbm"));
        return bean;
    }

    @Bean
    public PlatformTransactionManager transactionManager(final SessionFactory sessionFactory) {
        return new HibernateTransactionManager(sessionFactory);
    }

    @Bean
    public TransactionTemplate transactionTemplate(final PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(final DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(final DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public DataSourceInitializer xnatStubTables(final DataSource dataSource) {
        final DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(new ClassPathResource("db/xnat-stub.sql")));
        return initializer;
    }

    @Bean
    public XnatItemQueries xnatItemQueries(final JdbcTemplate jdbcTemplate) {
        return new XnatItemQueries(jdbcTemplate);
    }

    @Bean
    public BatchTransferHistoryDAO batchTransferHistoryDAO() {
        return new BatchTransferHistoryDAO();
    }

    @Bean
    public BatchTransferItemDAO batchTransferItemDAO() {
        return new BatchTransferItemDAO();
    }

    @Bean
    public BatchTransferHistoryService batchTransferHistoryService(final BatchTransferItemDAO itemDao,
                                                                  final BatchTransferProgressService progressService,
                                                                  final XnatItemQueries xnatItems) {
        return new HibernateBatchTransferHistoryService(itemDao, progressService, xnatItems);
    }

    @Bean
    public BatchTransferItemService batchTransferItemService(final BatchTransferHistoryDAO historyDao,
                                                            final BatchTransferProgressService progressService) {
        return new HibernateBatchTransferItemService(historyDao, progressService);
    }

    @Bean
    public BatchTransferProgressDAO batchTransferProgressDAO() {
        return new BatchTransferProgressDAO();
    }

    @Bean
    public BatchTransferProgressService batchTransferProgressService() {
        return new HibernateBatchTransferProgressService();
    }
}
