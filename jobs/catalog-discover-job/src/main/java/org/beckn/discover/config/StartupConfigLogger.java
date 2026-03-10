package org.beckn.discover.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Logs key infrastructure settings at startup so we can verify benchmarking
 * config.
 */
@Component
public class StartupConfigLogger {

    private static final Logger logger = LoggerFactory.getLogger(StartupConfigLogger.class);

    private final DataSource dataSource;
    private final ServerProperties serverProperties;
    private final DiscoveryProperties discoveryProperties;

    public StartupConfigLogger(DataSource dataSource,
            ServerProperties serverProperties,
            DiscoveryProperties discoveryProperties) {
        this.dataSource = dataSource;
        this.serverProperties = serverProperties;
        this.discoveryProperties = discoveryProperties;
    }

    @PostConstruct
    public void logConfig() {
        logHikariSettings();
        logTomcatSettings();
        logDiscoverySettings();
    }

    private void logHikariSettings() {
        if (dataSource instanceof HikariDataSource hikari) {
            var dsProps = hikari.getDataSourceProperties();
            logger.info(
                    "Hikari config -> maxPoolSize={}, minIdle={}, preparedStatementCacheQueries={}, preparedStatementCacheSizeMiB={}",
                    hikari.getMaximumPoolSize(),
                    hikari.getMinimumIdle(),
                    dsProps.getProperty("preparedStatementCacheQueries", "n/a"),
                    dsProps.getProperty("preparedStatementCacheSizeMiB", "n/a"));
        } else {
            logger.warn("DataSource is not a HikariDataSource; skipping pool config logging");
        }
    }

    private void logTomcatSettings() {
        if (serverProperties.getTomcat() != null && serverProperties.getTomcat().getThreads() != null) {
            var threads = serverProperties.getTomcat().getThreads();
            logger.info("Tomcat threads -> max={}, minSpare={}", threads.getMax(), threads.getMinSpare());
        } else {
            logger.info("Tomcat thread settings not customized (using defaults).");
        }

        if (serverProperties.getTomcat() != null) {
            logger.info("Tomcat acceptCount={}, maxConnections={}",
                    serverProperties.getTomcat().getAcceptCount(),
                    serverProperties.getTomcat().getMaxConnections());
        }
    }

    private void logDiscoverySettings() {
        if (discoveryProperties.getPostgresql() != null) {
            logger.info("Discovery PostgreSQL limit -> resultLimit={}, logExplainAnalyze={}",
                    discoveryProperties.getPostgresql().getResultLimit(),
                    discoveryProperties.getPostgresql().isLogExplainAnalyze());
        }
    }
}
