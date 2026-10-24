package core.compare;

import com.sleepycat.persist.EntityCursor;
import config.profile.SqlColProfile;
import core.filter.AshFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import lombok.Getter;
import org.rtv.Options;
import profile.IProfile;
import store.StoreManager;
import store.entity.database.MainData;

/**
 * Two periods of the local history side by side: average active sessions (AAS) per wait class and per
 * value of a column (SQL_ID, MODULE...), normalized by the collected time of each period
 */
public class PeriodComparison {
    /** Pseudo column: SESSION_ID + serial# */
    public static final String SESSION = "SESSION";
    /** Dimension with the execution plans of each value */
    public static final String SQL_ID = "SQL_ID";
    private static final String PLAN_COL = "SQL_PLAN_HASH_VALUE";
    private static final String EXEC_ID_COL = "SQL_EXEC_ID";
    private static final String EXEC_START_COL = "SQL_EXEC_START";
    /** Executions or time per execution changed from 20% (B / A): sampled executions are estimates */
    private static final double CAUSE_RATIO = 1.2;

    /** Collection stopped (tunnel down, app closed): not counted as time of the period */
    private static final long MIN_GAP_MS = 60_000;

    @Getter private final Period a;
    @Getter private final Period b;
    /** Columns of the "Comparar por" combo that are collected */
    @Getter private final List<String> dimensions;

    private PeriodComparison(Period a, Period b, List<String> dimensions) {
        this.a = a;
        this.b = b;
        this.dimensions = dimensions;
    }

    /**
     * @param candidates columns offered in "Comparar por" (kept when collected), {@link #SESSION} included
     * @return null when stopped by {@code stale}
     */
    public static PeriodComparison load(StoreManager storeManager, IProfile iProfile, AshFilter.Matcher matcher,
                                        long[] rangeA, long[] rangeB, List<String> candidates, BooleanSupplier stale) {
        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        if (cols == null) cols = Collections.emptyList();

        Map<String, SqlColProfile> dimensionCols = new LinkedHashMap<>();
        SqlColProfile sessionCol = findCol(cols, iProfile.getSessColName()).orElse(null);
        SqlColProfile serialCol = findCol(cols, iProfile.getSerialColName()).orElse(null);
        for (String candidate : candidates) {
            if (SESSION.equals(candidate)) {
                if (sessionCol != null) dimensionCols.put(SESSION, sessionCol);
            } else {
                findCol(cols, candidate).ifPresent(c -> dimensionCols.put(candidate, c));
            }
        }
        SqlColProfile waitClassCol = findCol(cols, iProfile.getWaitClassColName()).orElse(null);
        SqlColProfile eventCol = findCol(cols, iProfile.getEventColName()).orElse(null);
        SqlColProfile planCol = dimensionCols.containsKey(SQL_ID) ? findCol(cols, PLAN_COL).orElse(null) : null;
        SqlColProfile execIdCol = dimensionCols.containsKey(SQL_ID) ? findCol(cols, EXEC_ID_COL).orElse(null) : null;
        SqlColProfile execStartCol = findCol(cols, EXEC_START_COL).orElse(null);

        // v$active_session_history: no wait class / event when on CPU (as in the charts)
        String cpuLabel = iProfile.getWaitClass((byte) 0);
        Reader reader = new Reader(matcher, dimensionCols, sessionCol, serialCol, waitClassCol, eventCol, planCol, cpuLabel,
                execIdCol, execStartCol);
        Period a = reader.read(storeManager, rangeA, stale);
        Period b = a == null ? null : reader.read(storeManager, rangeB, stale);
        if (b == null) {
            return null;
        }
        return new PeriodComparison(a, b, new ArrayList<>(dimensionCols.keySet()));
    }

    /** Values of a column in both periods, the biggest increase first */
    public List<Item> items(String dimension, Measure measure) {
        Map<String, Counter> inA = a.byDimension.getOrDefault(dimension, Collections.emptyMap());
        Map<String, Counter> inB = b.byDimension.getOrDefault(dimension, Collections.emptyMap());
        List<Item> items = items(inA, inB, measure);
        if (SQL_ID.equals(dimension)) {
            items.forEach(i -> {
                i.plansA = plans(a, i.value);
                i.plansB = plans(b, i.value);
                i.execA = a.execs.get(i.value);
                i.execB = b.execs.get(i.value);
            });
        }
        return items;
    }

    /** SQL_IDs run with a plan in B not seen in A */
    public long planChanges() {
        return a.plans.keySet().stream()
                .filter(sql -> isPlanChanged(plans(a, sql), plans(b, sql)))
                .count();
    }

    /** Executions were collected (SQL_EXEC_ID) in both periods */
    public boolean hasExecs() {
        return !a.execs.isEmpty() && !b.execs.isEmpty();
    }

    /** Execution plans were collected (SQL_PLAN_HASH_VALUE) in both periods */
    public boolean hasPlans() {
        return !a.plans.isEmpty() && !b.plans.isEmpty();
    }

    /** Plans of a SQL_ID, the most sampled first */
    private static List<String> plans(Period period, String sqlId) {
        Map<String, Long> counts = period.plans.getOrDefault(sqlId, Collections.emptyMap());
        List<String> out = new ArrayList<>(counts.keySet());
        out.sort((x, y) -> Long.compare(counts.get(y), counts.get(x)));
        return out;
    }

    private static boolean isPlanChanged(List<String> plansA, List<String> plansB) {
        return !plansA.isEmpty() && !plansB.isEmpty() && !plansA.containsAll(plansB);
    }

    public List<Item> waitClasses(Measure measure) {
        return items(a.byWaitClass, b.byWaitClass, measure);
    }

    private List<Item> items(Map<String, Counter> inA, Map<String, Counter> inB, Measure measure) {
        Map<String, Item> items = new LinkedHashMap<>();
        inA.forEach((value, c) -> items.computeIfAbsent(value, v -> new Item(v, measure)).samplesA = c.samples);
        inB.forEach((value, c) -> {
            Item item = items.computeIfAbsent(value, v -> new Item(v, measure));
            item.samplesB = c.samples;
            item.topEvent = c.topEvent();
        });
        items.values().forEach(item -> {
            if (item.topEvent == null) {
                Counter c = inA.get(item.value);
                item.topEvent = c == null ? "" : c.topEvent();
            }
        });
        List<Item> out = new ArrayList<>(items.values());
        out.sort((x, y) -> Double.compare(y.getDelta(), x.getDelta()));
        return out;
    }

    private static Optional<SqlColProfile> findCol(List<SqlColProfile> cols, String name) {
        return name == null ? Optional.empty()
                : cols.stream().filter(c -> c.getColName().equalsIgnoreCase(name)).findFirst();
    }

    /**
     * AVERAGE: average active sessions, to compare the load of two time windows;
     * TOTAL: DB time in seconds, to compare two runs of the same work (a batch, a procedure)
     */
    public enum Measure {
        AVERAGE(0.01), TOTAL(1);

        /** Smaller differences are "estável" */
        @Getter private final double minDelta;

        Measure(double minDelta) {
            this.minDelta = minDelta;
        }

        public double of(Period period, long samples) {
            return this == AVERAGE ? period.aas(samples) : period.seconds(samples);
        }
    }

    /** Row of the comparison tables, values in the measure of the comparison */
    public class Item {
        @Getter private final String value;
        @Getter private final Measure measure;
        private long samplesA;
        private long samplesB;
        @Getter private String topEvent;
        /** Execution plans (SQL_ID only), the most sampled first */
        @Getter private List<String> plansA = Collections.emptyList();
        @Getter private List<String> plansB = Collections.emptyList();
        /** Executions seen in the samples (SQL_ID only), null when none */
        private Executions execA;
        private Executions execB;

        Item(String value, Measure measure) {
            this.value = value;
            this.measure = measure;
        }

        public double getA() {
            return measure.of(a, samplesA);
        }

        public double getB() {
            return measure.of(b, samplesB);
        }

        public double getDelta() {
            return getB() - getA();
        }

        /** Change in percent, NaN when not in A */
        public double getChange() {
            return samplesA == 0 ? Double.NaN : getDelta() * 100 / getA();
        }

        /** Executions seen in A, -1 when unknown */
        public long getExecutionsA() {
            return execA == null ? -1 : execA.count();
        }

        public long getExecutionsB() {
            return execB == null ? -1 : execB.count();
        }

        /** DB time per execution in seconds, NaN when unknown */
        public double getTimePerExecA() {
            return execA == null ? Double.NaN : execA.secondsPerExecution(a);
        }

        public double getTimePerExecB() {
            return execB == null ? Double.NaN : execB.secondsPerExecution(b);
        }

        /** Why it went up or down: more / fewer executions, slower / faster executions, or both */
        public Cause getCause() {
            State state = getState();
            if (execA == null || execB == null || (state != State.UP && state != State.DOWN)) {
                return Cause.NONE;
            }
            double executions = (double) execB.count() / execA.count();
            double time = getTimePerExecB() / getTimePerExecA();
            if (state == State.UP) {
                boolean more = executions >= CAUSE_RATIO;
                boolean slower = time >= CAUSE_RATIO;
                return more && slower ? Cause.MORE_AND_SLOWER : more ? Cause.MORE : slower ? Cause.SLOWER : Cause.NONE;
            }
            boolean fewer = executions <= 1 / CAUSE_RATIO;
            boolean faster = time <= 1 / CAUSE_RATIO;
            return fewer && faster ? Cause.FEWER_AND_FASTER : fewer ? Cause.FEWER : faster ? Cause.FASTER : Cause.NONE;
        }

        /** B used a plan not seen in A */
        public boolean isPlanChanged() {
            return PeriodComparison.isPlanChanged(plansA, plansB);
        }

        public State getState() {
            double va = getA();
            double vb = getB();
            if (samplesA == 0) return State.NEW;
            if (samplesB == 0) return State.GONE;
            if (Math.abs(vb - va) < measure.getMinDelta()) return State.SAME;
            if (vb >= va * (1 + CHANGE)) return State.UP;
            if (vb <= va * (1 - CHANGE)) return State.DOWN;
            return State.SAME;
        }
    }

    public enum State { NEW, GONE, UP, DOWN, SAME }

    public enum Cause { NONE, MORE, SLOWER, MORE_AND_SLOWER, FEWER, FASTER, FEWER_AND_FASTER }

    /** Executions of a SQL_ID in a period: SQL_EXEC_ID + SQL_EXEC_START -> samples */
    static class Executions {
        private final Map<String, Long> samples = new HashMap<>();

        long count() {
            return samples.size();
        }

        double secondsPerExecution(Period period) {
            long rows = samples.values().stream().mapToLong(Long::longValue).sum();
            return period.seconds(rows) / samples.size();
        }
    }

    /** Up from +5%, down from -5% (B compared to A) */
    private static final double CHANGE = 0.05;

    /** One period: samples per value and the time really collected */
    @Getter
    public static class Period {
        private final long begin;
        private final long end;
        private long rows;
        private long cpuRows;
        private int samples;
        /** Time between two samples (median), 1 s when unknown */
        private long sampleMs = 1000;
        /** Time of the period without collection (gaps, beginning and end) */
        private long missingMs;

        private final Map<String, Counter> byWaitClass = new HashMap<>();
        private final Map<String, Map<String, Counter>> byDimension = new HashMap<>();
        /** SQL_ID -> plan hash value -> samples (without plan: not kept) */
        private final Map<String, Map<String, Long>> plans = new HashMap<>();
        /** SQL_ID -> executions (samples without SQL_EXEC_ID: not kept) */
        private final Map<String, Executions> execs = new HashMap<>();

        Period(long begin, long end) {
            this.begin = begin;
            this.end = end;
        }

        public long getCollectedMs() {
            return Math.max(0, end - begin - missingMs);
        }

        /** Collected time / period, 0..1 */
        public double getCoverage() {
            return end <= begin ? 0 : (double) getCollectedMs() / (end - begin);
        }

        public double total(Measure measure) {
            return measure.of(this, rows);
        }

        public double cpu(Measure measure) {
            return measure.of(this, cpuRows);
        }

        double aas(long rowCount) {
            long collected = getCollectedMs();
            return collected == 0 ? 0 : (double) rowCount * sampleMs / collected;
        }

        /** DB time: each sampled active session counts as the time between two samples */
        double seconds(long rowCount) {
            return rowCount * sampleMs / 1000.0;
        }
    }

    static class Counter {
        long samples;
        private final Map<String, Long> events = new HashMap<>();

        void add(String event) {
            samples++;
            if (event != null) events.merge(event, 1L, Long::sum);
        }

        String topEvent() {
            return events.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        }
    }

    private static class Reader {
        private final AshFilter.Matcher matcher;
        private final Map<String, SqlColProfile> dimensionCols;
        private final SqlColProfile sessionCol;
        private final SqlColProfile serialCol;
        private final SqlColProfile waitClassCol;
        private final SqlColProfile eventCol;
        private final SqlColProfile planCol;
        private final String cpuLabel;
        private final SqlColProfile execIdCol;
        private final SqlColProfile execStartCol;

        Reader(AshFilter.Matcher matcher, Map<String, SqlColProfile> dimensionCols, SqlColProfile sessionCol,
               SqlColProfile serialCol, SqlColProfile waitClassCol, SqlColProfile eventCol, SqlColProfile planCol,
               String cpuLabel, SqlColProfile execIdCol, SqlColProfile execStartCol) {
            this.matcher = matcher;
            this.dimensionCols = dimensionCols;
            this.sessionCol = sessionCol;
            this.serialCol = serialCol;
            this.waitClassCol = waitClassCol;
            this.eventCol = eventCol;
            this.planCol = planCol;
            this.cpuLabel = cpuLabel;
            this.execIdCol = execIdCol;
            this.execStartCol = execStartCol;
        }

        Period read(StoreManager storeManager, long[] range, BooleanSupplier stale) {
            Period period = new Period(range[0], range[1]);
            List<Long> keys = new ArrayList<>();

            EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                    .entities(range[0], true, range[1], false);
            try {
                for (MainData sl : cursor) {
                    if (stale.getAsBoolean()) {
                        return null;
                    }
                    keys.add(sl.getKey());
                    int rowCount = sl.getMainMatrix().length;
                    for (int i = 0; i < rowCount; i++) {
                        if (!matcher.matches(sl, i)) {
                            continue;
                        }
                        String waitClass = cpuIfEmpty(value(waitClassCol, sl, i));
                        String event = cpuIfEmpty(value(eventCol, sl, i));
                        period.rows++;
                        if (Options.LBL_CPU.equals(waitClass) || Options.LBL_PG_CPU.equals(waitClass)) {
                            period.cpuRows++;
                        }
                        period.byWaitClass.computeIfAbsent(waitClass, k -> new Counter()).add(event);
                        for (Map.Entry<String, SqlColProfile> d : dimensionCols.entrySet()) {
                            String v = SESSION.equals(d.getKey())
                                    ? value(sessionCol, sl, i) + "," + value(serialCol, sl, i)
                                    : d.getValue() == eventCol ? event : value(d.getValue(), sl, i);
                            period.byDimension.computeIfAbsent(d.getKey(), k -> new HashMap<>())
                                    .computeIfAbsent(v, k -> new Counter()).add(event);
                        }
                        if (execIdCol != null) {
                            String sqlId = value(dimensionCols.get(SQL_ID), sl, i);
                            String execId = value(execIdCol, sl, i);
                            if (!sqlId.isEmpty() && !execId.isEmpty()) {
                                // ids restart: the start time tells two executions with the same id apart
                                String start = execStartCol == null ? "" : value(execStartCol, sl, i);
                                period.execs.computeIfAbsent(sqlId, k -> new Executions()).samples
                                        .merge(execId + "|" + start, 1L, Long::sum);
                            }
                        }
                        if (planCol != null) {
                            String sqlId = value(dimensionCols.get(SQL_ID), sl, i);
                            String plan = value(planCol, sl, i);
                            // 0: no plan (PL/SQL call, statement being parsed)
                            if (!sqlId.isEmpty() && !plan.isEmpty() && !"0".equals(plan)) {
                                period.plans.computeIfAbsent(sqlId, k -> new HashMap<>()).merge(plan, 1L, Long::sum);
                            }
                        }
                    }
                }
            } finally {
                cursor.close();
            }
            collectedTime(period, keys);
            return period;
        }

        private String cpuIfEmpty(String value) {
            return value.isEmpty() && cpuLabel != null ? cpuLabel : value;
        }

        private String value(SqlColProfile col, MainData sl, int row) {
            return col == null ? "" : matcher.getValue(col, sl, row);
        }

        /** Sample interval and time without collection, from the sample times */
        private static void collectedTime(Period period, List<Long> keys) {
            period.samples = keys.size();
            if (keys.isEmpty()) {
                period.missingMs = period.end - period.begin;
                return;
            }
            List<Long> deltas = new ArrayList<>();
            for (int i = 1; i < keys.size(); i++) {
                deltas.add(keys.get(i) - keys.get(i - 1));
            }
            if (!deltas.isEmpty()) {
                List<Long> sorted = new ArrayList<>(deltas);
                Collections.sort(sorted);
                period.sampleMs = Math.max(1, sorted.get(sorted.size() / 2));
            }
            long gap = Math.max(MIN_GAP_MS, 30 * period.sampleMs);
            long missing = 0;
            for (long d : deltas) {
                if (d > gap) missing += d - period.sampleMs;
            }
            long head = keys.get(0) - period.begin;
            long tail = period.end - keys.get(keys.size() - 1);
            if (head > gap) missing += head;
            if (tail > gap) missing += tail;
            period.missingMs = Math.min(missing, period.end - period.begin);
        }
    }
}
