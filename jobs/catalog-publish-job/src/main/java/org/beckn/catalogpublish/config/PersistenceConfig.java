package org.beckn.catalogpublish.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * DataSource and TransactionTemplate for per-catalog transaction boundaries.
 */
@Configuration
public class PersistenceConfig {

    @Bean
    public DataSource dataSource(AppProperties props) {
        AppProperties.Datasource ds = props.datasource();
        AppProperties.Hikari h = ds.hikari();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.url());
        cfg.setDriverClassName(ds.driverClassName());
        cfg.setUsername(ds.username());
        cfg.setPassword(ds.password() != null ? ds.password() : "");
        cfg.setMaximumPoolSize(h.maximumPoolSize());
        cfg.setMinimumIdle(h.minimumIdle());
        cfg.setConnectionTimeout(h.connectionTimeout());
        cfg.setIdleTimeout(h.idleTimeout());
        cfg.setMaxLifetime(h.maxLifetime());
        return new HikariDataSource(cfg);
    }

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager txManager) {
        return new TransactionTemplate(txManager);
    }
}
