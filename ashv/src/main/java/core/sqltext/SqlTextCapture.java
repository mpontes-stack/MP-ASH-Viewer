package core.sqltext;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Every 10 minutes, the texts of the SQL_IDs collected since the last run are read from v$sql (in batches) and kept
 * in the local history, before they age out of the shared pool. The tooltips and "Copiar texto do SQL" of every
 * screen read that history first, also days later and when only the history is open.
 */
@Slf4j
public final class SqlTextCapture {
    private static final long INTERVAL_MS = 10 * 60_000L;
    private static ScheduledExecutorService executor;
    private static long lastEnd;

    private SqlTextCapture() {}

    /** While collecting (Oracle): first run after a minute, over the last 10 minutes */
    public static synchronized void start() {
        if (executor != null) {
            return;
        }
        lastEnd = System.currentTimeMillis() - INTERVAL_MS;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sql-text-capture");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        executor.scheduleWithFixedDelay(SqlTextCapture::run, 1, 10, TimeUnit.MINUTES);
    }

    private static void run() {
        SqlTexts texts = SqlTexts.get();
        if (texts == null) {
            return;
        }
        long end = System.currentTimeMillis();
        try {
            int saved = texts.captureRecent(lastEnd, end);
            if (saved >= 0) {
                // a failed run (database not reachable) is retried over the same window
                lastEnd = end;
            }
            if (saved != 0) {
                log.info("SQL text capture: " + saved + " texts saved");
            }
        } catch (Exception e) {
            log.error("SQL text capture", e);
        }
    }
}
