package com.tom500101.rlcompass.client;

/**
 * Tracks an in-flight compass search on the client so the HUD can show progress.
 *
 * <p>Two very different waits share this indicator. A synchronous lookup answers within a second,
 * while a tick-sliced deep scan can run for tens of minutes; the latter therefore gets its own
 * deadline and a flag the picker uses to turn its search button into a cancel button.
 */
public final class ClientSearchStatus {

    /** Give up showing progress after this long, so a lost packet cannot pin the indicator. */
    private static final long TIMEOUT_MS = 30_000L;
    /** Deep scans are allowed to run for hours; the server always sends a terminal packet. */
    private static final long DEEP_TIMEOUT_MS = 4L * 60L * 60L * 1000L;

    private static volatile long deadline;
    private static volatile boolean deepScan;

    private ClientSearchStatus() {
    }

    /** A normal (synchronous) locator lookup. */
    public static void begin() {
        deadline = System.currentTimeMillis() + TIMEOUT_MS;
        deepScan = false;
    }

    /** A tick-sliced deep scan: long wait, no blocking, cancellable from the picker. */
    public static void beginDeepScan() {
        deadline = System.currentTimeMillis() + DEEP_TIMEOUT_MS;
        deepScan = true;
    }

    public static void end() {
        deadline = 0L;
        deepScan = false;
    }

    public static boolean isSearching() {
        long until = deadline;
        if (until == 0L) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            deadline = 0L;
            deepScan = false;
            return false;
        }
        return true;
    }

    /** True only while a deep scan is the thing being waited on. */
    public static boolean isDeepScan() {
        return deepScan && isSearching();
    }
}
