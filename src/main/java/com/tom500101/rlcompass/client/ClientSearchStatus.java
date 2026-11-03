package com.tom500101.rlcompass.client;

/**
 * Tracks an in-flight compass search on the client so the HUD can show progress.
 *
 * <p>The request/response pair is fire-and-forget over the network, and a themed search can take a
 * second or more on the server; without this the screen simply stays unchanged until the answer
 * arrives, which reads as "the button did nothing".
 */
public final class ClientSearchStatus {

    /** Give up showing progress after this long, so a lost packet cannot pin the indicator. */
    private static final long TIMEOUT_MS = 30_000L;

    private static volatile long startedAt;

    private ClientSearchStatus() {
    }

    public static void begin() {
        startedAt = System.currentTimeMillis();
    }

    public static void end() {
        startedAt = 0L;
    }

    public static boolean isSearching() {
        long start = startedAt;
        if (start == 0L) {
            return false;
        }
        if (System.currentTimeMillis() - start > TIMEOUT_MS) {
            startedAt = 0L;
            return false;
        }
        return true;
    }
}
