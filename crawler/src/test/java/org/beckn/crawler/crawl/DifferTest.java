package org.beckn.crawler.crawl;

import org.beckn.crawler.model.FeedModels.Index;
import org.beckn.crawler.state.StateStore;
import org.beckn.crawler.state.StateStore.PartState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the pure change-detection logic (design doc §5.4 step 4).
 * The StateStore is mocked so each scenario controls exactly "what I saw last time".
 */
class DifferTest {

    private StateStore state;
    private Differ differ;

    @BeforeEach
    void setUp() {
        state = mock(StateStore.class);
        differ = new Differ(state);
    }

    private Index indexOf(Index.Record... records) {
        return new Index("dedi-file", new Index.Publisher("prov.example"),
                "beckn-catalogs", null, List.of(records));
    }

    private Index.Record record(String catalogId, long version, String status, Object visibility,
                                Index.Part... parts) {
        Index.Details details = new Index.Details(catalogId, version, "generic", status,
                visibility, "2026-07-20T00:00:00Z", List.of(parts));
        return new Index.Record(catalogId, details);
    }

    private Index.Part part(String url, String digest) {
        return new Index.Part(url, digest, "2026-07-20T00:00:00Z");
    }

    private PartState stored(String url, long version, String digest) {
        return new PartState(url, "any", version, digest, null, Instant.now());
    }

    @Test
    void unseenPart_isPush() {
        when(state.findPart("u1")).thenReturn(Optional.empty());
        Index idx = indexOf(record("C1", 1, "ACTIVE", "public", part("u1", "sha-256:aaa")));

        List<Differ.Decision> decisions = differ.diff(idx);

        assertThat(decisions).singleElement()
                .satisfies(d -> {
                    assertThat(d.action()).isEqualTo(Differ.Action.PUSH);
                    assertThat(d.changedParts()).extracting(Index.Part::url).containsExactly("u1");
                });
    }

    @Test
    void changedDigest_isPush() {
        when(state.findPart("u1")).thenReturn(Optional.of(stored("u1", 1, "sha-256:old")));
        Index idx = indexOf(record("C1", 2, "ACTIVE", "public", part("u1", "sha-256:new")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Differ.Action.PUSH));
    }

    @Test
    void sameDigest_isSkipUnchanged() {
        when(state.findPart("u1")).thenReturn(Optional.of(stored("u1", 1, "sha-256:same")));
        Index idx = indexOf(record("C1", 1, "ACTIVE", "public", part("u1", "sha-256:same")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Differ.Action.SKIP_UNCHANGED));
    }

    @Test
    void digestCompareIsCaseInsensitive_isSkipUnchanged() {
        when(state.findPart("u1")).thenReturn(Optional.of(stored("u1", 1, "sha-256:ABCDEF")));
        Index idx = indexOf(record("C1", 1, "ACTIVE", "public", part("u1", "sha-256:abcdef")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Differ.Action.SKIP_UNCHANGED));
    }

    @Test
    void retiredStatus_isRetire() {
        Index idx = indexOf(record("C1", 1, "RETIRED", "public", part("u1", "sha-256:x")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> {
                    assertThat(d.action()).isEqualTo(Differ.Action.RETIRE);
                    assertThat(d.changedParts()).isEmpty();
                });
    }

    @Test
    void nonActiveStatus_isSkipInactive() {
        // Any status that is neither ACTIVE nor RETIRED (e.g. DRAFT) is skipped.
        Index idx = indexOf(record("C1", 1, "DRAFT", "public", part("u1", "sha-256:x")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> {
                    assertThat(d.action()).isEqualTo(Differ.Action.SKIP_INACTIVE);
                    assertThat(d.detail()).contains("DRAFT");
                });
    }

    @Test
    void activeStatus_isPushed() {
        when(state.findPart("u1")).thenReturn(Optional.empty());
        Index idx = indexOf(record("C1", 1, "ACTIVE", "public", part("u1", "sha-256:x")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Differ.Action.PUSH));
    }

    @Test
    void nonPublicVisibility_isSkipNonPublic() {
        // visibility is an object (network-scoped) rather than the literal "public"
        Object scoped = java.util.Map.of("networks", List.of("net-1"));
        Index idx = indexOf(record("C1", 1, "ACTIVE", scoped, part("u1", "sha-256:x")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Differ.Action.SKIP_NON_PUBLIC));
    }

    @Test
    void lowerVersionThanStored_isSkipRollback() {
        when(state.findPart("u1")).thenReturn(Optional.of(stored("u1", 5, "sha-256:old")));
        Index idx = indexOf(record("C1", 3, "ACTIVE", "public", part("u1", "sha-256:new")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> assertThat(d.action()).isEqualTo(Differ.Action.SKIP_ROLLBACK));
    }

    @Test
    void multiPart_onlyChangedPartsCarried() {
        when(state.findPart("u1")).thenReturn(Optional.of(stored("u1", 1, "sha-256:same")));
        when(state.findPart("u2")).thenReturn(Optional.of(stored("u2", 1, "sha-256:old")));
        Index idx = indexOf(record("C1", 2, "ACTIVE", "public",
                part("u1", "sha-256:same"), part("u2", "sha-256:new")));

        assertThat(differ.diff(idx)).singleElement()
                .satisfies(d -> {
                    assertThat(d.action()).isEqualTo(Differ.Action.PUSH);
                    assertThat(d.changedParts()).extracting(Index.Part::url).containsExactly("u2");
                });
    }
}
