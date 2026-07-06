package br.com.archflow.api.config;

import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.utils.ConnectionProvider;
import org.quartz.utils.DBConnectionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Constrói um {@link Scheduler} Quartz com {@code JDBCJobStore} (tabelas
 * {@code QRTZ_*}), para que triggers agendados sobrevivam a restart — ao
 * contrário do {@code RAMJobStore} default.
 *
 * <p>Reaproveita o {@link DataSource} do Spring via um {@link ConnectionProvider}
 * nomeado, sem o Quartz abrir um pool próprio. Extraído como helper para ser
 * exercitado por teste de integração sem subir todo o contexto.
 */
final class DurableQuartzScheduler {

    private DurableQuartzScheduler() {
    }

    /**
     * Cria (sem iniciar) um scheduler durável ligado ao {@code dataSource}.
     *
     * @param dataSource          fonte de conexões (pool do Spring)
     * @param driverDelegateClass delegate JDBC do Quartz (ex.: PostgreSQLDelegate)
     * @param instanceName        nome da instância do scheduler (SCHED_NAME)
     */
    static Scheduler create(DataSource dataSource, String driverDelegateClass, String instanceName)
            throws SchedulerException {
        String dsName = instanceName + "-ds";
        DBConnectionManager.getInstance()
                .addConnectionProvider(dsName, new SpringDataSourceConnectionProvider(dataSource));

        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", instanceName);
        props.setProperty("org.quartz.scheduler.instanceId", "AUTO");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "4");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.impl.jdbcjobstore.JobStoreTX");
        props.setProperty("org.quartz.jobStore.driverDelegateClass", driverDelegateClass);
        props.setProperty("org.quartz.jobStore.dataSource", dsName);
        props.setProperty("org.quartz.jobStore.tablePrefix", "QRTZ_");
        props.setProperty("org.quartz.jobStore.useProperties", "false");

        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        return factory.getScheduler();
    }

    /** Expõe o {@link DataSource} do Spring ao Quartz, delegando cada conexão. */
    static final class SpringDataSourceConnectionProvider implements ConnectionProvider {

        private final DataSource dataSource;

        SpringDataSourceConnectionProvider(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return dataSource.getConnection();
        }

        @Override
        public void shutdown() {
            // O ciclo de vida do pool pertence ao Spring, não ao Quartz.
        }

        @Override
        public void initialize() {
            // DataSource já inicializado pelo Spring.
        }
    }
}
