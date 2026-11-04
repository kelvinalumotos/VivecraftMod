package org.vivecraft.client_vr.provider.openxr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * QuestCraft diagnostic. On Quest the app was killed by Android with "Can't deliver broadcast": the
 * kernel logged "binder_alloc_buf ... no vma", meaning the process' binder mapping had disappeared, so
 * the next broadcast (battery status) could not be delivered. Something in the process unmaps it.
 * This checks /proc/self/maps every two seconds and, when the binder mapping changes, logs when it
 * happened and what now occupies its addresses, to find the culprit.
 */
public final class BinderWatch {
    private static final Logger LOGGER = LoggerFactory.getLogger("QuestCraft");
    private static final Path MAPS = Path.of("/proc/self/maps");
    private static boolean started = false;

    private BinderWatch() {}

    public static synchronized void start() {
        if (started || !Files.isReadable(MAPS)) {
            return;
        }
        started = true;
        Thread thread = new Thread(BinderWatch::run, "QuestCraft binder watch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void run() {
        try {
            List<String> initial = binderLines(Files.readAllLines(MAPS));
            if (initial.isEmpty()) {
                // off Android there is none; on Android it would already be gone
                if (Files.exists(Path.of("/system/build.prop"))) {
                    LOGGER.error("QuestCraft: no binder mapping at startup, it is already gone");
                }
                return;
            }
            for (String line : initial) {
                LOGGER.info("QuestCraft: binder mapping {}", line);
            }
            while (true) {
                Thread.sleep(2000);
                List<String> maps = Files.readAllLines(MAPS);
                List<String> now = binderLines(maps);
                if (now.equals(initial)) {
                    continue;
                }
                LOGGER.error(
                    "QuestCraft: BINDER MAPPING CHANGED - Android will kill the app at the next broadcast");
                LOGGER.error("QuestCraft: before: {}", initial);
                LOGGER.error("QuestCraft: now:    {}", now);
                for (String line : initial) {
                    long[] range = range(line);
                    for (String other : maps) {
                        long[] r = range(other);
                        if (r != null && range != null && r[0] < range[1] && r[1] > range[0]) {
                            LOGGER.error("QuestCraft: now at those addresses: {}", other);
                        }
                    }
                }
                Runtime rt = Runtime.getRuntime();
                LOGGER.error("QuestCraft: Java heap used {} MB of {} MB, {} mappings",
                    (rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20, maps.size());
                return;
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable t) {
            LOGGER.warn("QuestCraft: binder watch stopped", t);
        }
    }

    private static List<String> binderLines(List<String> maps) {
        List<String> lines = new ArrayList<>();
        for (String line : maps) {
            if (line.contains("/dev/binder") || line.contains("/dev/binderfs/")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static long[] range(String line) {
        try {
            int dash = line.indexOf('-');
            int space = line.indexOf(' ');
            if (dash <= 0 || space <= dash) {
                return null;
            }
            return new long[]{
                Long.parseUnsignedLong(line.substring(0, dash), 16),
                Long.parseUnsignedLong(line.substring(dash + 1, space), 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
