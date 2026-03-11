package org.beckn.catalogpublish.util;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs a block with MDC set from a snapshot; optionally clears MDC in finally
 * so worker threads do not leak context. Use for async/executor boundaries.
 */
public final class MdcSupport {

    private MdcSupport() {}

    /**
     * Sets MDC from {@code snapshot} (if non-null), runs {@code task}, then clears MDC in
     * finally when {@code clearAfterRun} is true (e.g. pool threads; use false when
     * CallerRunsPolicy runs inline so the caller's MDC is not cleared).
     */
    public static <T> T runWithSnapshot(Map<String, String> snapshot, boolean clearAfterRun, Supplier<T> task) {
        if (snapshot != null) {
            MDC.setContextMap(snapshot);
        }
        try {
            return task.get();
        } finally {
            if (clearAfterRun) {
                MDC.clear();
            }
        }
    }

    /**
     * Void variant of {@link #runWithSnapshot(Map, boolean, Supplier)}.
     */
    public static void runWithSnapshot(Map<String, String> snapshot, boolean clearAfterRun, Runnable task) {
        runWithSnapshot(snapshot, clearAfterRun, () -> {
            task.run();
            return null;
        });
    }
}
