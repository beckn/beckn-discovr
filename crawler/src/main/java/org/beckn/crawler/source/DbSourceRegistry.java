package org.beckn.crawler.source;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Manifest sources from the {@code crawler_source} table (crawler.source=db). Only active rows
 * ({@code status = true}) are returned, re-read on every call so UI additions/removals take effect
 * within one index poll.
 */
@Component
@ConditionalOnProperty(name = "crawler.source", havingValue = "db")
public class DbSourceRegistry implements SourceRegistry {

    private final JdbcClient jdbc;

    public DbSourceRegistry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CrawlerSource> sources() {
        return jdbc.sql("""
                SELECT dedi_url, display_name
                  FROM crawler_source
                 WHERE status = true
                 ORDER BY created_at
                """)
                .query((rs, n) -> new CrawlerSource(rs.getString("dedi_url"), rs.getString("display_name")))
                .list();
    }
}
