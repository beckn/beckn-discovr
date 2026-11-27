package org.beckn.catalogpublish.indexing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 100M / Tier-B shard plan: 5 primaries + 1 replica per catalogs-* index
 * (architecture §3.2 — ~20–50 GB/shard; cluster-wide 80–200 primaries via
 * per-schema indexes).
 */
class EsIndexTemplateShardPlanTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
            "config/es-index-template.json",
            "discovr-deployment/elasticsearch/es-index-template.json",
            "discovr-deployment/helm/charts/catalog-publish-job/files/es-index-template.json"
    })
    void templateFilesDeclareTierBShardReplicaPlan(String relativePath) throws Exception {
        Path path = resolveFromRepoRoot(relativePath);
        assertThat(path).as(relativePath).exists();
        JsonNode settings = MAPPER.readTree(Files.readString(path))
                .path("template").path("settings");
        assertThat(settings.path("index.number_of_shards").asInt())
                .as("%s shards", relativePath).isEqualTo(5);
        assertThat(settings.path("index.number_of_replicas").asInt())
                .as("%s replicas", relativePath).isEqualTo(1);
    }

    @Test
    void builtInDefaultTemplateSourceDeclaresSameShardReplicaPlan() throws Exception {
        Path source = resolveFromRepoRoot(
                "jobs/catalog-publish-job/src/main/java/org/beckn/catalogpublish/indexing/EsIndexManager.java");
        assertThat(source).exists();
        String java = Files.readString(source);
        assertThat(java).contains("\"index.number_of_shards\": 5");
        assertThat(java).contains("\"index.number_of_replicas\": 1");
    }

    private static Path resolveFromRepoRoot(String relativePath) {
        Path cwd = Path.of("").toAbsolutePath();
        Path direct = cwd.resolve(relativePath);
        if (Files.exists(direct)) {
            return direct;
        }
        // Gradle cwd is the job module directory
        Path fromRepo = cwd.resolve("../..").resolve(relativePath).normalize();
        if (Files.exists(fromRepo)) {
            return fromRepo;
        }
        return direct;
    }
}
