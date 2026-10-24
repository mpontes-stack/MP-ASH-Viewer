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

        Reader reader = new Reader(matcher, dimensionCols, sessionCol, serialCol, waitClassCol, eventCol);
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
        return items(inA, inB, measure);
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

        Reader(AshFilter.Matcher matcher, Map<String, SqlColProfile> dimensionCols, SqlColProfile sessionCol,
               SqlColProfile serialCol, SqlColProfile waitClassCol, SqlColProfile eventCol) {
            this.matcher = matcher;
            this.dimensionCols = dimensionCols;
            this.sessionCol = sessionCol;
            this.serialCol = serialCol;
            this.waitClassCol = waitClassCol;
            this.eventCol = eventCol;
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
                        String waitClass = value(waitClassCol, sl, i);
                        String event = value(eventCol, sl, i);
                        period.rows++;
                        if (Options.LBL_CPU.equals(waitClass) || Options.LBL_PG_CPU.equals(waitClass)) {
                            period.cpuRows++;
                        }
                        period.byWaitClass.computeIfAbsent(waitClass, k -> new Counter()).add(event);
                        for (Map.Entry<String, SqlColProfile> d : dimensionCols.entrySet()) {
                            String v = SESSION.equals(d.getKey())
                                    ? value(sessionCol, sl, i) + "," + value(serialCol, sl, i)
                                    : value(d.getValue(), sl, i);
                            period.byDimension.computeIfAbsent(d.getKey(), k -> new HashMap<>())
                                    .computeIfAbsent(v, k -> new Counter()).add(event);
                        }
                    }
                }
            } finally {
                cursor.close();
            }
            collectedTime(period, keys);
            return period;
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
