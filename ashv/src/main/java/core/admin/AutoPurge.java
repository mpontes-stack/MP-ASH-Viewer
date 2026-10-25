package core.admin;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import store.StoreManager;

/**
 * Automatic purge of the open connection: samples older than its "keep days" (Admin tab), a few minutes after
 * opening and then once a day. Nothing happens while the connection keeps everything (the default).
 */
@Slf4j
public final class AutoPurge {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static ScheduledExecutorService executor;

    private AutoPurge() {}

    public static synchronized void start(StoreManager storeManager, String connName) {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "auto-purge");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> run(storeManager, connName), 3, 24 * 60, TimeUnit.MINUTES);
    }

    /** One purge of the open connection now (also used by the Admin tab after changing the days) */
    public static void run(StoreManager storeManager, String connName) {
        long cutoff = HistoryStore.keepCutoff(connName, System.currentTimeMillis());
        if (cutoff <= 0 || !RUNNING.compareAndSet(false, true)) {
            return;
        }
        try {
            long n = HistoryStore.purgeOlder(connName, cutoff, storeManager, () -> false, (step, done, total) -> { });
            log.info("Automatic purge of " + connName + ": " + n + " samples older than "
                    + HistoryStore.getKeepDays(connName) + " days");
        } catch (Exception e) {
            log.error("Automatic purge of " + connName, e);
        } finally {
            RUNNING.set(false);
        }
    }

    /** A purge (automatic or from the Admin tab) is running */
    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static boolean begin() {
        return RUNNING.compareAndSet(false, true);
    }

    public static void end() {
        RUNNING.set(false);
    }
}
