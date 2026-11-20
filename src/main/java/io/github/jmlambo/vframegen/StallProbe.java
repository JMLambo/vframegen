package io.github.jmlambo.vframegen;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Debug (temporary): when a game loop takes much longer than usual, log how long each part of it
 * took, and whether the garbage collector ran, so stalls can be pinned to a cause.
 */
public final class StallProbe {
    private static final long THRESHOLD_NS = 35_000_000L;
    private static final List<String> names = new ArrayList<>();
    private static final List<Long> times = new ArrayList<>();
    private static long gcCount, gcMs;
    private static int logged;

    private StallProbe() {
    }

    public static void mark(String name) {
        if (!VFrameGen.config().debugStallLog) return;
        names.add(name);
        times.add(System.nanoTime());
    }

    /** Start of a loop: check the one before. */
    public static void loopStart() {
        if (!VFrameGen.config().debugStallLog) {
            if (!times.isEmpty()) {
                names.clear();
                times.clear();
            }
            return;
        }
        long now = System.nanoTime();
        long[] gc = gc();
        if (!times.isEmpty()) {
            long total = now - times.get(0);
            if (total > THRESHOLD_NS && logged < 200) {
                logged++;
                StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "[stall debug] loop took %.1f ms:", total / 1e6));
                for (int i = 1; i < times.size(); i++) {
                    b.append(String.format(Locale.ROOT, " %s +%.1f", names.get(i), (times.get(i) - times.get(i - 1)) / 1e6));
                }
                b.append(String.format(Locale.ROOT, " | next loop +%.1f", (now - times.get(times.size() - 1)) / 1e6));
                b.append(String.format(Locale.ROOT, " | GC: %d runs, %d ms", gc[0] - gcCount, gc[1] - gcMs));
                VFrameGen.LOGGER.info(b.toString());
            }
        }
        gcCount = gc[0];
        gcMs = gc[1];
        names.clear();
        times.clear();
        mark("loopStart");
    }

    private static long[] gc() {
        long count = 0, ms = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            ms += Math.max(0, bean.getCollectionTime());
        }
        return new long[]{count, ms};
    }
}
