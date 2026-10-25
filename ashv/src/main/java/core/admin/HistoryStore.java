package core.admin;

import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.EnvironmentLockedException;
import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import com.sleepycat.persist.StoreConfig;
import config.FileConfig;
import config.Labels;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.prefs.Preferences;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import store.StoreManager;
import store.entity.database.MainData;
import store.entity.olap.AshAggrMinData;
import store.entity.olap.AshAggrMinData15Sec;
import store.entity.olap.AshAggrMinData1Min;
import store.entity.olap.CompositeKey;

/**
 * Local history of every connection (database/&lt;connection&gt;): size, period, purge of old samples and of everything,
 * days to keep per connection (automatic purge). The open connection uses the store of the app; the others are
 * opened for the task and closed.
 */
@Slf4j
public final class HistoryStore {
    private static final String STORE_NAME = "ash.db";
    // own node: the "ASH-Viewer" node keeps the connection secrets
    private static final String PREFERENCES_NODE = "MP-ASH-Viewer/retain";
    private static final long DAY = 24 * 3600_000L;

    private HistoryStore() {}

    /** History of one connection */
    @Getter
    public static final class Info {
        private final String name;
        private final long bytes;
        /** First / last sample (ms), 0 when unknown or empty */
        private long first;
        private long last;
        /** null: read; otherwise why the period could not be read (e.g. used by another program) */
        private String error;

        Info(String name, long bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    public static File databaseDir() {
        return new File(FileConfig.DATABASE_DIR);
    }

    /** Connections with a history folder, the biggest first (period not read yet) */
    public static List<Info> list() {
        List<Info> out = new ArrayList<>();
        File[] dirs = databaseDir().listFiles(File::isDirectory);
        if (dirs == null) {
            return out;
        }
        for (File dir : dirs) {
            if (new File(dir, "je.properties").exists() || hasLogFiles(dir) || isEmptyDir(dir)) {
                out.add(new Info(dir.getName(), size(dir)));
            }
        }
        out.sort(Comparator.comparingLong(Info::getBytes).reversed());
        return out;
    }

    /** First and last sample: from the open store or reading the folder (read only) */
    public static void readPeriod(Info info, StoreManager storeManager) {
        try {
            if (isOpen(info.name, storeManager)) {
                period(info, storeManager.getBerkleyDB().getStore());
                return;
            }
            if (!hasLogFiles(new File(databaseDir(), info.name))) {
                return;
            }
            try (Opened o = open(info.name, true)) {
                period(info, o.store);
            }
        } catch (EnvironmentLockedException e) {
            info.error = Labels.getLabel("gui.admin.locked");
        } catch (Exception e) {
            info.error = e.getMessage();
            log.info("History period of " + info.name + ": " + e.getMessage());
        }
    }

    /** SQL texts kept in the history open in the app (tooltips, captured every 10 minutes), -1 when unknown */
    public static long sqlTextCount(StoreManager storeManager) {
        try {
            return storeManager.getBerkleyDB().getStore()
                    .getPrimaryIndex(String.class, store.entity.database.ParamStringString.class).count();
        } catch (Exception e) {
            return -1;
        }
    }

    /** The history open in the app (collecting or shown) */
    public static boolean isOpen(String name, StoreManager storeManager) {
        String dir = storeManager.getBerkleyDB().getDirectory();
        return dir != null && storeManager.getBerkleyDB().getStore() != null
                && new File(dir).getAbsoluteFile().equals(new File(databaseDir(), name).getAbsoluteFile());
    }

    /**
     * Delete samples and History aggregates older than {@code cutoff} (ms) and give the disk space back
     *
     * @return samples deleted, -1 when stopped (what was deleted until then stays deleted)
     */
    public static long purgeOlder(String name, long cutoff, StoreManager storeManager, BooleanSupplier stop,
                                  Progress progress) throws Exception {
        File dir = new File(databaseDir(), name);
        long[] kept = new long[2];
        if (isOpen(name, storeManager)) {
            EntityStore store = storeManager.getBerkleyDB().getStore();
            long n = purge(store, cutoff, stop, progress, kept);
            if (n >= 0) compact(storeManager.getBerkleyDB().getEnv(), store, dir, keepShare(kept), progress, stop);
            return stop.getAsBoolean() ? -1 : n;
        }
        try (Opened o = open(name, false)) {
            long n = purge(o.store, cutoff, stop, progress, kept);
            if (n >= 0) compact(o.env, o.store, dir, keepShare(kept), progress, stop);
            return stop.getAsBoolean() ? -1 : n;
        }
    }

    /** Share of the samples kept (kept, deleted) */
    private static double keepShare(long[] kept) {
        long all = kept[0] + kept[1];
        return all == 0 ? 1 : (double) kept[0] / all;
    }

    /** Delete the whole history of a connection that is not open (the connection itself is kept) */
    public static void deleteAll(String name, StoreManager storeManager) throws IOException {
        if (isOpen(name, storeManager)) {
            throw new IllegalStateException("open connection");
        }
        // fails while another program uses it
        open(name, false).close();
        Path dir = new File(databaseDir(), name).toPath();
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
            });
        }
    }

    /** Days of history to keep (automatic purge), 0: keep everything */
    public static int getKeepDays(String name) {
        try {
            return Preferences.userRoot().node(PREFERENCES_NODE).getInt(key(name), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void setKeepDays(String name, int days) {
        try {
            Preferences node = Preferences.userRoot().node(PREFERENCES_NODE);
            if (days > 0) {
                node.putInt(key(name), days);
            } else {
                node.remove(key(name));
            }
            node.flush();
        } catch (Exception e) {
            log.info("Keep days preference: " + e.getMessage());
        }
    }

    /** Cutoff of the automatic purge (ms), 0 when the connection keeps everything */
    public static long keepCutoff(String name, long now) {
        int days = getKeepDays(name);
        return days > 0 ? now - days * DAY : 0;
    }

    private static String key(String name) {
        // preference keys are limited to 80 characters
        return name.length() <= 80 ? name : name.substring(0, 70) + Integer.toHexString(name.hashCode());
    }

    private static void period(Info info, EntityStore store) {
        PrimaryIndex<Long, MainData> index = store.getPrimaryIndex(Long.class, MainData.class);
        try (EntityCursor<Long> keys = index.keys()) {
            Long first = keys.first();
            Long last = keys.last();
            info.first = first == null ? 0 : first;
            info.last = last == null ? 0 : last;
        }
    }

    /** Steps of a purge, for a progress bar */
    public enum Step { COUNT, DELETE, COMPACT }

    public interface Progress {
        /**
         * @param done  items deleted (DELETE) or bytes given back to the disk (COMPACT); items counted (COUNT)
         * @param total items to delete or bytes expected to be freed; -1 while unknown
         */
        void update(Step step, long done, long total);
    }

    /** Samples and aggregates older than the cutoff, read with key cursors only (no sample contents) */
    private static long purge(EntityStore store, long cutoff, BooleanSupplier stop, Progress progress, long[] kept) {
        PrimaryIndex<Long, MainData> samples = store.getPrimaryIndex(Long.class, MainData.class);
        List<SecondaryIndex<Long, CompositeKey, ?>> aggregates = Arrays.asList(
                byDate(store, AshAggrMinData.class), byDate(store, AshAggrMinData15Sec.class),
                byDate(store, AshAggrMinData1Min.class));

        // count first: the total of the progress bar
        long total = 0;
        long counted = 0;
        try (EntityCursor<Long> keys = samples.keys(0L, true, cutoff, false)) {
            for (Long k = keys.first(); k != null; k = keys.nextNoDup()) {
                if ((++total & 0xFFF) == 0) {
                    if (stop.getAsBoolean()) return -1;
                    progress.update(Step.COUNT, total, -1);
                }
            }
        }
        long samplesToDelete = total;
        for (SecondaryIndex<Long, CompositeKey, ?> index : aggregates) {
            try (EntityCursor<Long> keys = index.keys(0L, true, cutoff, false)) {
                for (Long k = keys.first(); k != null; k = keys.next()) {
                    if ((++total & 0xFFF) == 0) progress.update(Step.COUNT, total, -1);
                }
            }
        }
        try (EntityCursor<Long> keys = samples.keys(cutoff, true, Long.MAX_VALUE, true)) {
            for (Long k = keys.first(); k != null; k = keys.nextNoDup()) {
                counted++;
            }
        }
        kept[0] = counted;
        kept[1] = samplesToDelete;

        long done = 0;
        try (EntityCursor<Long> keys = samples.keys(0L, true, cutoff, false)) {
            for (Long k = keys.first(); k != null; k = keys.next()) {
                if (stop.getAsBoolean()) return stopped(store);
                keys.delete();
                if ((++done & 0x3FF) == 0) progress.update(Step.DELETE, done, total);
            }
        }
        for (SecondaryIndex<Long, CompositeKey, ?> index : aggregates) {
            try (EntityCursor<Long> keys = index.keys(0L, true, cutoff, false)) {
                for (Long k = keys.first(); k != null; k = keys.next()) {
                    if (stop.getAsBoolean()) return stopped(store);
                    keys.delete();
                    if ((++done & 0x3FF) == 0) progress.update(Step.DELETE, done, total);
                }
            }
        }
        progress.update(Step.DELETE, total, total);
        store.sync();
        return samplesToDelete;
    }

    /** Stopped while deleting: what was deleted stays deleted */
    private static long stopped(EntityStore store) {
        store.sync();
        return -1;
    }

    private static <E> SecondaryIndex<Long, CompositeKey, E> byDate(EntityStore store, Class<E> type) {
        PrimaryIndex<CompositeKey, E> primary = store.getPrimaryIndex(CompositeKey.class, type);
        return store.getSecondaryIndex(primary, Long.class, "dateId");
    }

    /**
     * Berkeley DB frees disk space by cleaning its log files and checkpointing. Progress: bytes given back, against
     * the size expected for the samples kept ({@code keepShare} of the folder when it starts)
     */
    private static void compact(Environment env, EntityStore store, File dir, double keepShare, Progress progress,
                                BooleanSupplier stop) {
        store.sync();
        long start = size(dir);
        long toFree = Math.max(1, Math.round(start * (1 - keepShare)));
        progress.update(Step.COMPACT, 0, toFree);
        int cleaned;
        int rounds = 0;
        CheckpointConfig force = new CheckpointConfig();
        force.setForce(true);
        do {
            cleaned = env.cleanLog();
            // cleaned files are deleted at the next checkpoint
            if (cleaned > 0 && rounds % 5 == 4) env.checkpoint(force);
            progress.update(Step.COMPACT, Math.min(toFree, Math.max(0, start - size(dir))), toFree);
        } while (cleaned > 0 && ++rounds < 100_000 && !stop.getAsBoolean());
        env.checkpoint(force);
        progress.update(Step.COMPACT, toFree, toFree);
    }

    private static Opened open(String name, boolean readOnly) {
        File dir = new File(databaseDir(), name);
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(false);
        envConfig.setReadOnly(readOnly);
        envConfig.setTransactional(false);
        // the app may run with a small heap (-Xmx128m)
        envConfig.setCacheSize(24L * 1024 * 1024);
        Environment env = new Environment(dir, envConfig);
        try {
            StoreConfig storeConfig = new StoreConfig();
            storeConfig.setAllowCreate(!readOnly);
            storeConfig.setReadOnly(readOnly);
            storeConfig.setTransactional(false);
            storeConfig.setDeferredWrite(!readOnly);
            return new Opened(env, new EntityStore(env, STORE_NAME, storeConfig));
        } catch (RuntimeException e) {
            env.close();
            throw e;
        }
    }

    private static final class Opened implements AutoCloseable {
        final Environment env;
        final EntityStore store;

        Opened(Environment env, EntityStore store) {
            this.env = env;
            this.store = store;
        }

        @Override
        public void close() {
            try {
                store.close();
            } finally {
                env.close();
            }
        }
    }

    private static boolean hasLogFiles(File dir) {
        String[] logs = dir.list((d, n) -> n.endsWith(".jdb"));
        return logs != null && logs.length > 0;
    }

    private static boolean isEmptyDir(File dir) {
        String[] files = dir.list();
        return files != null && files.length == 0;
    }

    private static long size(File dir) {
        File[] files = dir.listFiles();
        return files == null ? 0 : Arrays.stream(files).filter(File::isFile).mapToLong(File::length).sum();
    }
}
