package core.admin;

import com.sleepycat.persist.EntityCursor;
import com.sleepycat.persist.EntityStore;
import com.sleepycat.persist.PrimaryIndex;
import com.sleepycat.persist.SecondaryIndex;
import config.Labels;
import config.profile.ConfigProfile;
import config.profile.ConnProfile;
import config.profile.SqlColProfile;
import core.manager.ConfigurationManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import store.StoreManager;
import store.dao.database.ParameterDoubleDAO;
import store.dao.database.ParameterStringDAO;
import store.entity.database.MainData;
import store.entity.database.ParamStringString;
import store.entity.database.ParameterDouble;
import store.entity.database.ParameterString;
import store.entity.database.SqlPlan;
import store.entity.olap.AshAggrMinData;
import store.entity.olap.AshAggrMinData15Sec;
import store.entity.olap.AshAggrMinData1Min;
import store.entity.olap.AshParameter;
import store.entity.olap.AshUser;
import store.entity.olap.AshWaitEvent;
import store.entity.olap.CompositeKey;

/**
 * Backup of a period of a local history: a .zip with backup.properties (origin, period, counts), the connection
 * without user / password / URL (connection.yaml) and a ready Berkeley DB (history/). Import into a connection with an
 * empty history (any name), or into the connection of the same name when the period does not overlap its data.
 */
@Slf4j
public final class HistoryBackup {
    public static final String MANIFEST = "backup.properties";
    public static final String CONFIG = "connection.yaml";
    private static final String HISTORY = "history/";
    private static final String SQL_ID = "SQL_ID";
    private static final String FORMAT = "yyyy-MM-dd HH:mm:ss";
    private static final int NULL = Integer.MIN_VALUE;

    private HistoryBackup() {}

    /** What a backup holds (backup.properties + connection.yaml) */
    @Getter
    public static final class Manifest {
        private String origin;
        private String profile;
        private long from;
        private long to;
        private long samples;
        private long sqlTexts;
        private String exportedAt;
        private ConfigProfile config;
    }

    /* ---------------------------------------------------------------- export */

    /**
     * @param from first sample (ms, inclusive), {@code to} last sample (inclusive)
     * @return samples exported, -1 when stopped (no file is left)
     */
    public static long export(String name, long from, long to, File zip, StoreManager storeManager,
                              ConfigurationManager configurationManager, BooleanSupplier stop,
                              HistoryStore.Progress progress) throws Exception {
        ConfigProfile config = configurationManager.getConnProfileList().stream()
                .filter(c -> c.getConfigName().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException(String.format(Labels.getLabel("gui.backup.noconfig"), name)));
        Path temp = Files.createTempDirectory("ashv-export");
        try {
            long[] counts;
            if (HistoryStore.isOpen(name, storeManager)) {
                counts = copyPeriod(storeManager.getBerkleyDB().getStore(), temp.toFile(), from, to, config, stop, progress);
            } else {
                try (HistoryStore.Opened source = HistoryStore.open(name, true)) {
                    counts = copyPeriod(source.store, temp.toFile(), from, to, config, stop, progress);
                }
            }
            if (counts == null) {
                return -1;
            }
            Map<String, String> manifest = new java.util.LinkedHashMap<>();
            SimpleDateFormat f = new SimpleDateFormat(FORMAT);
            manifest.put("source.connection", name);
            manifest.put("profile", config.getConnProfile().getProfileName());
            // the samples really exported (the period asked may start before the history)
            manifest.put("period.start", f.format(new Date(counts[2] > 0 ? counts[2] : from)));
            manifest.put("period.end", f.format(new Date(counts[3] > 0 ? counts[3] : to)));
            manifest.put("samples", String.valueOf(counts[0]));
            manifest.put("sql.texts", String.valueOf(counts[1]));
            manifest.put("exported.at", f.format(new Date()));
            manifest.put("exported.by", gui.custom.AppLogo.NAME + " " + gui.custom.AppLogo.VERSION);
            if (!zip(temp.toFile(), zip, manifest, sanitized(config), stop, progress)) {
                Files.deleteIfExists(zip.toPath());
                return -1;
            }
            return counts[0];
        } finally {
            deleteDir(temp);
        }
    }

    /** @return {samples, SQL texts} or null when stopped */
    private static long[] copyPeriod(EntityStore source, File dir, long from, long to, ConfigProfile config,
                                     BooleanSupplier stop, HistoryStore.Progress progress) {
        PrimaryIndex<Long, MainData> samples = source.getPrimaryIndex(Long.class, MainData.class);
        long total = 0;
        try (EntityCursor<Long> keys = samples.keys(from, true, to, true)) {
            for (Long k = keys.first(); k != null; k = keys.next()) {
                if ((++total & 0xFFF) == 0) {
                    if (stop.getAsBoolean()) return null;
                    progress.update(HistoryStore.Step.COUNT, total, -1);
                }
            }
        }
        long sampleCount = total;
        total += count(byDate(source, AshAggrMinData.class), from, to) + count(byDate(source, AshAggrMinData15Sec.class), from, to)
                + count(byDate(source, AshAggrMinData1Min.class), from, to);

        int sqlIdIndex = colIndex(config.getSqlColProfileList(), SQL_ID);
        Set<Integer> sqlIdIds = new HashSet<>();
        long[] done = {0};
        try (HistoryStore.Opened target = HistoryStore.openDir(dir, false, true)) {
            EntityStore t = target.store;
            PrimaryIndex<Long, MainData> out = t.getPrimaryIndex(Long.class, MainData.class);
            long first = 0;
            long last = 0;
            try (EntityCursor<MainData> cursor = samples.entities(from, true, to, true)) {
                for (MainData m : cursor) {
                    if (stop.getAsBoolean()) return null;
                    out.put(m);
                    if (first == 0) first = m.getKey();
                    last = m.getKey();
                    if (sqlIdIndex >= 0) {
                        for (int[] row : m.getMainMatrix()) {
                            if (sqlIdIndex < row.length && row[sqlIdIndex] != NULL) sqlIdIds.add(row[sqlIdIndex]);
                        }
                    }
                    step(progress, HistoryStore.Step.COPY, ++done[0], total);
                }
            }
            if (!copyRange(byDate(source, AshAggrMinData.class), t.getPrimaryIndex(CompositeKey.class, AshAggrMinData.class),
                    from, to, stop, progress, done, total)
                    || !copyRange(byDate(source, AshAggrMinData15Sec.class), t.getPrimaryIndex(CompositeKey.class, AshAggrMinData15Sec.class),
                    from, to, stop, progress, done, total)
                    || !copyRange(byDate(source, AshAggrMinData1Min.class), t.getPrimaryIndex(CompositeKey.class, AshAggrMinData1Min.class),
                    from, to, stop, progress, done, total)) {
                return null;
            }

            // dictionaries: the samples keep their ids, the same ids and sequences in the backup
            int maxString = copyAll(source.getPrimaryIndex(Integer.class, ParameterString.class),
                    t.getPrimaryIndex(Integer.class, ParameterString.class), ParameterString::getParamId);
            int maxDouble = copyAll(source.getPrimaryIndex(Integer.class, ParameterDouble.class),
                    t.getPrimaryIndex(Integer.class, ParameterDouble.class), ParameterDouble::getParamId);
            int maxAshParameter = copyAll(source.getPrimaryIndex(Integer.class, AshParameter.class),
                    t.getPrimaryIndex(Integer.class, AshParameter.class), AshParameter::getParamId);
            int maxWaitEvent = copyAll(source.getPrimaryIndex(Integer.class, AshWaitEvent.class),
                    t.getPrimaryIndex(Integer.class, AshWaitEvent.class), AshWaitEvent::getEventId);
            copyAll(source.getPrimaryIndex(Integer.class, AshUser.class), t.getPrimaryIndex(Integer.class, AshUser.class), u -> 0);

            // SQL texts and plans of the SQL_IDs of the period only
            Set<String> sqlIds = new HashSet<>();
            PrimaryIndex<Integer, ParameterString> strings = source.getPrimaryIndex(Integer.class, ParameterString.class);
            for (Integer id : sqlIdIds) {
                ParameterString p = strings.get(id);
                if (p != null && p.getParamValue() != null && !p.getParamValue().isEmpty()) sqlIds.add(p.getParamValue());
            }
            PrimaryIndex<String, ParamStringString> texts = source.getPrimaryIndex(String.class, ParamStringString.class);
            PrimaryIndex<String, ParamStringString> textsOut = t.getPrimaryIndex(String.class, ParamStringString.class);
            long textCount = 0;
            for (String sqlId : sqlIds) {
                ParamStringString text = texts.get(sqlId);
                if (text != null) {
                    textsOut.put(text);
                    textCount++;
                }
            }
            long maxPlan = 0;
            PrimaryIndex<Long, SqlPlan> plans = source.getPrimaryIndex(Long.class, SqlPlan.class);
            PrimaryIndex<Long, SqlPlan> plansOut = t.getPrimaryIndex(Long.class, SqlPlan.class);
            SecondaryIndex<String, Long, SqlPlan> plansBySql = source.getSecondaryIndex(plans, String.class, "sqlId");
            for (String sqlId : sqlIds) {
                try (EntityCursor<SqlPlan> cursor = plansBySql.subIndex(sqlId).entities()) {
                    for (SqlPlan plan : cursor) {
                        plansOut.put(plan);
                        maxPlan = Math.max(maxPlan, plan.getSqlPlanId());
                    }
                }
            }

            advance(t, "StringParameterSeq", maxString);
            advance(t, "DoubleParameterSeq", maxDouble);
            advance(t, "AshParameterSeq", maxAshParameter);
            advance(t, "AshWaitEventSeq", maxWaitEvent);
            advance(t, "SqlPlanId", maxPlan);
            t.sync();
            return new long[]{sampleCount, textCount, first, last};
        }
    }

    private static <E> boolean copyRange(SecondaryIndex<Long, CompositeKey, E> from, PrimaryIndex<CompositeKey, E> to,
                                         long begin, long end, BooleanSupplier stop, HistoryStore.Progress progress,
                                         long[] done, long total) {
        try (EntityCursor<E> cursor = from.entities(begin, true, end, true)) {
            for (E e : cursor) {
                if (stop.getAsBoolean()) return false;
                to.put(e);
                step(progress, HistoryStore.Step.COPY, ++done[0], total);
            }
        }
        return true;
    }

    private static <E> int copyAll(PrimaryIndex<Integer, E> from, PrimaryIndex<Integer, E> to,
                                   java.util.function.ToIntFunction<E> id) {
        int max = 0;
        try (EntityCursor<E> cursor = from.entities()) {
            for (E e : cursor) {
                to.put(e);
                max = Math.max(max, id.applyAsInt(e));
            }
        }
        return max;
    }

    /** Next id of a sequence after the ids copied (new values collected later must not reuse them) */
    private static void advance(EntityStore store, String sequence, long max) {
        if (max > 0) {
            store.getSequence(sequence).get(null, (int) Math.min(Integer.MAX_VALUE, max));
        }
    }

    private static boolean zip(File dir, File zip, Map<String, String> manifest, ConfigProfile config, BooleanSupplier stop,
                               HistoryStore.Progress progress) throws IOException {
        File[] files = dir.listFiles(File::isFile);
        long total = HistoryStore.size(dir);
        long done = 0;
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(MANIFEST));
            // plain "key=value" lines, readable in any editor (values have no line breaks)
            Writer m = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            m.write("# MP-ASH-Viewer: local history backup\n");
            for (Map.Entry<String, String> e : manifest.entrySet()) {
                m.write(e.getKey() + "=" + e.getValue().replace("\\", "\\\\") + "\n");
            }
            m.flush();
            out.closeEntry();
            out.putNextEntry(new ZipEntry(CONFIG));
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            yaml().dump(config, w);
            w.flush();
            out.closeEntry();
            byte[] buffer = new byte[1 << 16];
            for (File f : files == null ? new File[0] : files) {
                if (f.getName().endsWith(".lck") || f.getName().startsWith("je.info")) continue;
                out.putNextEntry(new ZipEntry(HISTORY + f.getName()));
                try (InputStream in = Files.newInputStream(f.toPath())) {
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if (stop.getAsBoolean()) return false;
                        out.write(buffer, 0, n);
                        done += n;
                        step(progress, HistoryStore.Step.ZIP, done, total);
                    }
                }
                out.closeEntry();
            }
        }
        progress.update(HistoryStore.Step.ZIP, total, total);
        return true;
    }

    /** The connection without user, password, URL, driver jar and keys */
    private static ConfigProfile sanitized(ConfigProfile c) {
        ConfigProfile out = new ConfigProfile();
        out.setConfigName(c.getConfigName());
        out.setGroupName(c.getGroupName());
        out.setInitialLoading(c.getInitialLoading());
        out.setRawRetainDays(c.getRawRetainDays());
        out.setOlapRetainDays(c.getOlapRetainDays());
        out.setEncryptionType(c.getEncryptionType());
        out.setContainerType(c.getContainerType());
        ConnProfile conn = new ConnProfile();
        conn.setConnName(c.getConnProfile().getConnName());
        conn.setProfileName(c.getConnProfile().getProfileName());
        conn.setDriver(c.getConnProfile().getDriver());
        conn.setUrl("");
        conn.setUserName("");
        conn.setJar("");
        out.setConnProfile(conn);
        out.setSqlColProfileList(c.getSqlColProfileList());
        return out;
    }

    /* ---------------------------------------------------------------- import */

    public static Manifest readManifest(File zip) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            ZipEntry m = z.getEntry(MANIFEST);
            ZipEntry c = z.getEntry(CONFIG);
            if (m == null || c == null) {
                throw new IllegalStateException(String.format(Labels.getLabel("gui.backup.notbackup"), MANIFEST, CONFIG));
            }
            Properties p = new Properties();
            try (InputStream in = z.getInputStream(m)) {
                p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            Manifest out = new Manifest();
            out.origin = prop(p, "source.connection", "conexao.origem", "");
            out.profile = prop(p, "profile", "perfil", "");
            SimpleDateFormat f = new SimpleDateFormat(FORMAT);
            try {
                out.from = f.parse(prop(p, "period.start", "periodo.inicio", null)).getTime();
                out.to = f.parse(prop(p, "period.end", "periodo.fim", null)).getTime();
            } catch (Exception e) {
                throw new IllegalStateException(String.format(Labels.getLabel("gui.backup.badperiod"), MANIFEST));
            }
            out.samples = Long.parseLong(prop(p, "samples", "amostras", "0"));
            out.sqlTexts = Long.parseLong(prop(p, "sql.texts", "textos.sql", "0"));
            out.exportedAt = prop(p, "exported.at", "exportado.em", "");
            try (InputStream in = z.getInputStream(c)) {
                out.config = yaml().loadAs(new InputStreamReader(in, StandardCharsets.UTF_8), ConfigProfile.class);
            }
            return out;
        }
    }

    /** Key of backup.properties; backups of the first version used Portuguese keys */
    private static String prop(Properties p, String key, String oldKey, String def) {
        return p.getProperty(key, p.getProperty(oldKey, def));
    }

    /** Result of an import check: null when it can run, otherwise why not */
    public static String check(Manifest manifest, String target, ConfigurationManager configurationManager,
                               StoreManager storeManager) {
        if (storeManager != null && HistoryStore.isOpen(target, storeManager)) {
            return String.format(Labels.getLabel("gui.backup.open"), target);
        }
        long[] period = samplesPeriod(target);
        if (period == null) {
            return null; // empty: any connection
        }
        if (!target.equalsIgnoreCase(manifest.origin)) {
            return String.format(Labels.getLabel("gui.backup.other"), target, manifest.origin);
        }
        ConfigProfile config = config(configurationManager, target);
        if (config != null && !manifest.profile.equalsIgnoreCase(config.getConnProfile().getProfileName())) {
            return String.format(Labels.getLabel("gui.backup.profile"), manifest.profile, config.getConnProfile().getProfileName());
        }
        if (config != null && config.getSqlColProfileList() != null && !compatible(config.getSqlColProfileList(),
                manifest.config.getSqlColProfileList())) {
            return Labels.getLabel("gui.backup.columns");
        }
        if (overlaps(target, manifest.from, manifest.to)) {
            SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy HH:mm");
            return String.format(Labels.getLabel("gui.backup.overlap"), target, f.format(new Date(manifest.from)),
                    f.format(new Date(manifest.to)));
        }
        return null;
    }

    /**
     * Import into {@code target}: a new connection (created in the group "Importados"), an existing one with an empty
     * history, or the connection of the same name without samples in the period
     *
     * @return samples imported, -1 when stopped (nothing changed)
     */
    public static long importTo(File zip, Manifest manifest, String target, boolean newConnection,
                                ConfigurationManager configurationManager, BooleanSupplier stop,
                                HistoryStore.Progress progress) throws Exception {
        String problem = check(manifest, target, configurationManager, null);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        File database = HistoryStore.databaseDir();
        Path temp = Files.createTempDirectory(database.toPath(), ".import-");
        try {
            if (!unzip(zip, temp.toFile(), stop, progress)) {
                return -1;
            }
            File dir = new File(database, target);
            long imported;
            boolean empty = samplesPeriod(target) == null;
            if (empty) {
                // empty history: the backup becomes the history of the connection
                if (dir.exists()) deleteDir(dir.toPath());
                Files.move(temp, dir.toPath());
                imported = manifest.samples;
            } else {
                imported = merge(temp.toFile(), target, manifest, stop, progress);
                if (imported < 0) return -1;
            }
            saveConfig(configurationManager, manifest, target, newConnection, empty);
            return imported;
        } finally {
            if (Files.exists(temp)) deleteDir(temp);
        }
    }

    private static void saveConfig(ConfigurationManager configurationManager, Manifest manifest, String target,
                                   boolean newConnection, boolean empty) {
        ConfigProfile existing = config(configurationManager, target);
        ConfigProfile config;
        if (newConnection || existing == null) {
            config = manifest.config;
            config.setConfigName(target);
            config.getConnProfile().setConnName(target);
            config.setGroupName(Labels.getLabel("gui.import.group"));
            config.setRunning(false);
        } else {
            config = existing;
            config.getConnProfile().setProfileName(manifest.profile);
            // empty history: the columns of the backup; otherwise the longest list (columns are only appended)
            List<SqlColProfile> cols = manifest.config.getSqlColProfileList();
            if (empty || config.getSqlColProfileList() == null
                    || (cols != null && cols.size() > config.getSqlColProfileList().size())) {
                config.setSqlColProfileList(cols);
            }
        }
        configurationManager.loadConfigToFile(config);
    }

    /** Samples of the backup into the history of the same connection: values re-coded with its dictionaries */
    private static long merge(File backupDir, String targetName, Manifest manifest, BooleanSupplier stop,
                              HistoryStore.Progress progress) {
        List<SqlColProfile> cols = manifest.config.getSqlColProfileList();
        try (HistoryStore.Opened source = HistoryStore.openDir(backupDir, true, false);
             HistoryStore.Opened target = HistoryStore.open(targetName, false)) {
            EntityStore s = source.store;
            EntityStore t = target.store;
            PrimaryIndex<Long, MainData> samples = s.getPrimaryIndex(Long.class, MainData.class);
            long total = samples.count() + byDate(s, AshAggrMinData.class).count()
                    + byDate(s, AshAggrMinData15Sec.class).count() + byDate(s, AshAggrMinData1Min.class).count();
            Remap remap = new Remap(s, t);
            long done = 0;
            long imported = 0;
            PrimaryIndex<Long, MainData> out = t.getPrimaryIndex(Long.class, MainData.class);
            try (EntityCursor<MainData> cursor = samples.entities()) {
                for (MainData m : cursor) {
                    if (stop.getAsBoolean()) {
                        t.sync();
                        return -1;
                    }
                    int[][] matrix = m.getMainMatrix();
                    int[][] coded = new int[matrix.length][];
                    for (int r = 0; r < matrix.length; r++) {
                        coded[r] = new int[matrix[r].length];
                        for (int c = 0; c < matrix[r].length; c++) {
                            coded[r][c] = remap.cell(c < cols.size() ? cols.get(c).getColDbTypeName() : "", matrix[r][c]);
                        }
                    }
                    if (out.putNoOverwrite(new MainData(m.getKey(), coded))) imported++;
                    step(progress, HistoryStore.Step.MERGE, ++done, total);
                }
            }
            done = mergeAggregates(byDate(s, AshAggrMinData.class), t.getPrimaryIndex(CompositeKey.class, AshAggrMinData.class),
                    a -> new AshAggrMinData(a.getCompositeKey().getDateId(), remap.ashParameter(a.getCompositeKey().getParamId()),
                            remap.waitEvents(a.getWaitId()), a.getWaitClass(), a.getSum()), stop, progress, done, total);
            done = mergeAggregates(byDate(s, AshAggrMinData15Sec.class), t.getPrimaryIndex(CompositeKey.class, AshAggrMinData15Sec.class),
                    a -> new AshAggrMinData15Sec(a.getCompositeKey().getDateId(), remap.ashParameter(a.getCompositeKey().getParamId()),
                            remap.waitEvents(a.getWaitId()), a.getWaitClass(), a.getSum()), stop, progress, done, total);
            done = mergeAggregates(byDate(s, AshAggrMinData1Min.class), t.getPrimaryIndex(CompositeKey.class, AshAggrMinData1Min.class),
                    a -> new AshAggrMinData1Min(a.getCompositeKey().getDateId(), remap.ashParameter(a.getCompositeKey().getParamId()),
                            remap.waitEvents(a.getWaitId()), a.getWaitClass(), a.getSum()), stop, progress, done, total);
            if (done < 0) {
                t.sync();
                return -1;
            }
            // users and SQL texts: the ones the history does not have yet
            PrimaryIndex<Integer, AshUser> users = t.getPrimaryIndex(Integer.class, AshUser.class);
            try (EntityCursor<AshUser> cursor = s.getPrimaryIndex(Integer.class, AshUser.class).entities()) {
                for (AshUser u : cursor) users.putNoOverwrite(u);
            }
            PrimaryIndex<String, ParamStringString> texts = t.getPrimaryIndex(String.class, ParamStringString.class);
            try (EntityCursor<ParamStringString> cursor = s.getPrimaryIndex(String.class, ParamStringString.class).entities()) {
                for (ParamStringString text : cursor) texts.putNoOverwrite(text);
            }
            t.sync();
            return imported;
        }
    }

    private static <E> long mergeAggregates(SecondaryIndex<Long, CompositeKey, E> from, PrimaryIndex<CompositeKey, E> to,
                                            java.util.function.Function<E, E> recode, BooleanSupplier stop,
                                            HistoryStore.Progress progress, long done, long total) {
        if (done < 0) return done;
        try (EntityCursor<E> cursor = from.entities()) {
            for (E e : cursor) {
                if (stop.getAsBoolean()) return -1;
                to.putNoOverwrite(recode.apply(e));
                step(progress, HistoryStore.Step.MERGE, ++done, total);
            }
        }
        return done;
    }

    /** Ids of the backup dictionaries -> ids of the target dictionaries (created when missing) */
    private static final class Remap {
        private final PrimaryIndex<Integer, ParameterString> sourceStrings;
        private final PrimaryIndex<Integer, ParameterDouble> sourceDoubles;
        private final PrimaryIndex<Integer, AshParameter> sourceAshParameters;
        private final PrimaryIndex<Integer, AshWaitEvent> sourceWaitEvents;
        private final ParameterStringDAO targetStrings;
        private final ParameterDoubleDAO targetDoubles;
        private final PrimaryIndex<Integer, AshParameter> targetAshParameters;
        private final SecondaryIndex<String, Integer, AshParameter> targetAshParametersByValue;
        private final PrimaryIndex<Integer, AshWaitEvent> targetWaitEvents;
        private final Map<String, Integer> targetWaitEventsByKey = new HashMap<>();
        private final Map<Integer, Integer> strings = new HashMap<>();
        private final Map<Integer, Integer> doubles = new HashMap<>();
        private final Map<Integer, Integer> ashParameters = new HashMap<>();
        private final Map<Integer, Integer> waitEvents = new HashMap<>();

        Remap(EntityStore source, EntityStore target) {
            sourceStrings = source.getPrimaryIndex(Integer.class, ParameterString.class);
            sourceDoubles = source.getPrimaryIndex(Integer.class, ParameterDouble.class);
            sourceAshParameters = source.getPrimaryIndex(Integer.class, AshParameter.class);
            sourceWaitEvents = source.getPrimaryIndex(Integer.class, AshWaitEvent.class);
            targetStrings = new ParameterStringDAO(target);
            targetDoubles = new ParameterDoubleDAO(target);
            targetAshParameters = target.getPrimaryIndex(Integer.class, AshParameter.class);
            targetAshParametersByValue = target.getSecondaryIndex(targetAshParameters, String.class, "paramValue");
            targetWaitEvents = target.getPrimaryIndex(Integer.class, AshWaitEvent.class);
            try (EntityCursor<AshWaitEvent> cursor = targetWaitEvents.entities()) {
                for (AshWaitEvent e : cursor) targetWaitEventsByKey.put(e.getWaitClass() + "|" + e.getEventValue(), e.getEventId());
            }
        }

        /** A cell of a sample by the column type (see ConvertManager): strings and big numbers are dictionary ids */
        int cell(String type, int value) {
            if (value == NULL) return value;
            switch (type) {
                case "OID": case "TEXT": case "NAME": case "VARCHAR2": case "CHAR": case "RAW":
                    return strings.computeIfAbsent(value, v -> {
                        ParameterString p = sourceStrings.get(v);
                        return p == null ? NULL : targetStrings.getCheckOrLoadParameter(p.getParamValue());
                    });
                case "INT4": case "FLOAT8": case "NUMBER":
                    if (value >= 0) return value;
                    return doubles.computeIfAbsent(value, v -> {
                        ParameterDouble p = sourceDoubles.get(-v);
                        return p == null ? NULL : -targetDoubles.getCheckOrLoadParameter(p.getParamDValue());
                    });
                default:
                    return value;
            }
        }

        int ashParameter(int id) {
            return ashParameters.computeIfAbsent(id, v -> {
                AshParameter p = sourceAshParameters.get(v);
                if (p == null) return v;
                AshParameter existing = targetAshParametersByValue.get(p.getParamValue());
                if (existing != null) return existing.getParamId();
                AshParameter created = new AshParameter(0, p.getParamValue(), p.getAdditionalParams());
                targetAshParameters.put(created);
                return created.getParamId();
            });
        }

        int[] waitEvents(int[] ids) {
            int[] out = new int[ids.length];
            for (int i = 0; i < ids.length; i++) {
                out[i] = waitEvents.computeIfAbsent(ids[i], v -> {
                    AshWaitEvent e = sourceWaitEvents.get(v);
                    if (e == null) return v;
                    String key = e.getWaitClass() + "|" + e.getEventValue();
                    Integer existing = targetWaitEventsByKey.get(key);
                    if (existing != null) return existing;
                    AshWaitEvent created = new AshWaitEvent(0, e.getEventValue(), e.getWaitClass());
                    targetWaitEvents.put(created);
                    targetWaitEventsByKey.put(key, created.getEventId());
                    return created.getEventId();
                });
            }
            return out;
        }
    }

    private static boolean unzip(File zip, File dir, BooleanSupplier stop, HistoryStore.Progress progress) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            long total = z.stream().filter(e -> e.getName().startsWith(HISTORY)).mapToLong(ZipEntry::getSize).sum();
            long done = 0;
            byte[] buffer = new byte[1 << 16];
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                String name = e.getName();
                // only plain files of history/ (no paths outside the folder)
                if (!name.startsWith(HISTORY) || e.isDirectory() || name.substring(HISTORY.length()).contains("/")
                        || name.contains("..")) continue;
                File out = new File(dir, name.substring(HISTORY.length()));
                try (InputStream in = z.getInputStream(e); OutputStream o = Files.newOutputStream(out.toPath())) {
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if (stop.getAsBoolean()) return false;
                        o.write(buffer, 0, n);
                        done += n;
                        step(progress, HistoryStore.Step.UNZIP, done, total);
                    }
                }
            }
            progress.update(HistoryStore.Step.UNZIP, total, total);
        }
        return true;
    }

    /* ---------------------------------------------------------------- helpers */

    /** The connection already has samples (an import joins them) */
    public static boolean hasSamples(String name) {
        return samplesPeriod(name) != null;
    }

    /** First and last sample of a connection's history, null when it has none */
    private static long[] samplesPeriod(String name) {
        File dir = new File(HistoryStore.databaseDir(), name);
        String[] logs = dir.list((d, n) -> n.endsWith(".jdb"));
        if (logs == null || logs.length == 0) return null;
        try (HistoryStore.Opened o = HistoryStore.open(name, true)) {
            try (EntityCursor<Long> keys = o.store.getPrimaryIndex(Long.class, MainData.class).keys()) {
                Long first = keys.first();
                Long last = keys.last();
                return first == null ? null : new long[]{first, last};
            }
        } catch (RuntimeException e) {
            // e.g. an empty folder left by "Apagar tudo"
            log.info("History of " + name + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean overlaps(String name, long from, long to) {
        try (HistoryStore.Opened o = HistoryStore.open(name, true);
             EntityCursor<Long> keys = o.store.getPrimaryIndex(Long.class, MainData.class).keys(from, true, to, true)) {
            return keys.first() != null;
        }
    }

    /** Same columns in the same positions (one list may have more columns at the end) */
    private static boolean compatible(List<SqlColProfile> a, List<SqlColProfile> b) {
        if (a == null || b == null) return true;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            if (!a.get(i).getColName().equalsIgnoreCase(b.get(i).getColName())) return false;
        }
        return true;
    }

    private static ConfigProfile config(ConfigurationManager configurationManager, String name) {
        return configurationManager.getConnProfileList().stream()
                .filter(c -> c.getConfigName().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static int colIndex(List<SqlColProfile> cols, String name) {
        if (cols == null) return -1;
        for (SqlColProfile c : cols) {
            if (c.getColName().equalsIgnoreCase(name)) return c.getColId() - 1;
        }
        return -1;
    }

    private static <E> SecondaryIndex<Long, CompositeKey, E> byDate(EntityStore store, Class<E> type) {
        PrimaryIndex<CompositeKey, E> primary = store.getPrimaryIndex(CompositeKey.class, type);
        return store.getSecondaryIndex(primary, Long.class, "dateId");
    }

    private static long count(SecondaryIndex<Long, CompositeKey, ?> index, long from, long to) {
        long n = 0;
        try (EntityCursor<Long> keys = index.keys(from, true, to, true)) {
            for (Long k = keys.first(); k != null; k = keys.next()) n++;
        }
        return n;
    }

    private static void step(HistoryStore.Progress progress, HistoryStore.Step step, long done, long total) {
        if ((done & 0x3FF) == 0) progress.update(step, done, total);
    }

    /** Same YAML as the connection files (tag !ConfigProfile) */
    private static Yaml yaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setTagInspector(tag -> ConfigProfile.class.getName().equals(tag.getClassName()));
        Constructor constructor = new Constructor(loaderOptions);
        constructor.addTypeDescription(new TypeDescription(ConfigProfile.class, "!ConfigProfile"));
        Representer representer = new Representer(new DumperOptions());
        representer.addClassTag(ConfigProfile.class, new Tag("!ConfigProfile"));
        return new Yaml(constructor, representer);
    }

    static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    /** Default name of the file: Backup_<connection>_<from>_<to>.zip */
    public static String fileName(String name, long from, long to) {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd-HHmm");
        return "Backup_" + name.replaceAll("[^A-Za-z0-9._-]+", "_") + "_" + f.format(new Date(from)) + "_"
                + f.format(new Date(to)) + ".zip";
    }

    /** Folder of the histories, for the file chooser */
    public static File defaultDir() {
        return new File(System.getProperty("user.home"));
    }
}
