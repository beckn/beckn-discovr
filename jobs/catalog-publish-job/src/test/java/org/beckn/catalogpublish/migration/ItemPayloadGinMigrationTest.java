package org.beckn.catalogpublish.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the prod Flyway GIN on {@code item.payload} so it stays aligned with
 * discover IT schema ({@code idx_item_payload_gin} / {@code jsonb_path_ops}).
 */
class ItemPayloadGinMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V8__add_item_payload_gin.sql");

    @Test
    void v8CreatesPayloadGinWithJsonbPathOps() throws IOException {
        assertThat(MIGRATION).exists();
        String sql = Files.readString(MIGRATION);
        assertThat(sql).containsIgnoringCase("CREATE INDEX IF NOT EXISTS idx_item_payload_gin");
        assertThat(sql).containsIgnoringCase("USING GIN (payload jsonb_path_ops)");
    }
}
