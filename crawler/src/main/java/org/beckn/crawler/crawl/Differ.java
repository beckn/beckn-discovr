package org.beckn.crawler.crawl;

import org.beckn.crawler.model.FeedModels.Index;
import org.beckn.crawler.state.StateStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Step 4 (design doc §5.4): compare each index record against stored state and decide what to do.
 * Pure decision logic — no fetching or side effects (that's the Crawler's job).
 */
@Component
public class Differ {

    public enum Action { PUSH, RETIRE, SKIP_UNCHANGED, SKIP_NON_PUBLIC, SKIP_ROLLBACK }

    /** A decision for one catalog record. {@code changedParts} is populated only for PUSH. */
    public record Decision(Index.Record record, Action action, List<Index.Part> changedParts, String detail) {}

    private final StateStore state;

    public Differ(StateStore state) {
        this.state = state;
    }

    public List<Decision> diff(Index index) {
        List<Decision> decisions = new ArrayList<>();
        for (Index.Record record : index.records()) {
            decisions.add(decide(record));
        }
        return decisions;
    }

    private Decision decide(Index.Record record) {
        Index.Details d = record.details();

        if (d.isRetired()) {
            return new Decision(record, Action.RETIRE, List.of(), "status=RETIRED");
        }
        if (!d.isPublic()) {
            return new Decision(record, Action.SKIP_NON_PUBLIC, List.of(), "visibility!=public");
        }

        List<Index.Part> parts = d.parts() == null ? List.of() : d.parts();

        // Rollback guard: all parts of a catalog share its version. A decrease vs. any stored
        // part = rollback/tampering → skip the whole record.
        for (Index.Part part : parts) {
            var stored = state.findPart(part.url());
            if (stored.isPresent() && d.version() < stored.get().version()) {
                return new Decision(record, Action.SKIP_ROLLBACK, List.of(),
                        "version " + d.version() + " < stored " + stored.get().version());
            }
        }

        // Changed = never seen, or the stored digest differs from the announced one.
        List<Index.Part> changed = new ArrayList<>();
        for (Index.Part part : parts) {
            var stored = state.findPart(part.url());
            if (stored.isEmpty() || !part.digest().equalsIgnoreCase(stored.get().digest())) {
                changed.add(part);
            }
        }
        if (changed.isEmpty()) {
            return new Decision(record, Action.SKIP_UNCHANGED, List.of(), "all parts unchanged");
        }
        return new Decision(record, Action.PUSH, changed, changed.size() + " changed part(s)");
    }
}
