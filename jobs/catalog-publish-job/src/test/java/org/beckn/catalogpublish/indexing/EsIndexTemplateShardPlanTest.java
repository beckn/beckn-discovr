package org.beckn.catalogpublish.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shard/replica plan:
 * <ul>
 *   <li>Single-node Compose/stack templates: 1 primary / 0 replicas (avoid yellow).</li>
 *   <li>Helm (multi-node / Tier B prod starting point): 5 primaries / 1 replica.</li>
 * </ul>
 */
class EsIndexTemplateShardPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({
            "config/es-index-template.json, 1, 0",
            "discovr-deployment/elasticsearch/es-index-template.json, 1, 0",
            "discovr-deployment/helm/charts/catalog-publish-job/files/es-index-template.json, 5, 1"
    })
    void templateFilesDeclareExpectedShardReplicaPlan(String relativePath, int shards, int replicas)
            throws Exception {
        Path path = resolveFromRepoRoot(relativePath);
        assertThat(path).as(relativePath).exists();
        JsonNode settings = MAPPER.readTree(Files.readString(path))
                .path("template").path("settings");
        assertThat(settings.path("index.number_of_shards").asInt())
                .as("%s shards", relativePath).isEqualTo(shards);
        assertThat(settings.path("index.number_of_replicas").asInt())
                .as("%s replicas", relativePath).isEqualTo(replicas);
    }

    @Test
    void builtInDefaultTemplateMatchesSingleNodeOperatorTemplate() throws Exception {
        Path source = resolveFromRepoRoot(
                "jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/indexing/EsIndexManager.java");
        assertThat(source).exists();
        String java = Files.readString(source);
        assertThat(java).contains("\"index.number_of_shards\": 1");
        assertThat(java).contains("\"index.number_of_replicas\": 0");
    }

    private static Path resolveFromRepoRoot(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        Path fromRepo = cwd.resolve("../..").resolve(relativePath).normalize();
        if (Files.exists(fromRepo)) {
            return fromRepo;
        }
        return direct;
    }
}
