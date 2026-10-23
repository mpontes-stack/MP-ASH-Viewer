package core.filter;

import com.sleepycat.persist.EntityCursor;
import config.Labels;
import config.profile.SqlColProfile;
import core.manager.ConstantManager;
import gui.chart.CategoryTableXYDatasetRDA;
import gui.chart.panel.NameChartDataset;
import gui.chart.panel.StackChartPanel;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.util.GanttParam;
import profile.IProfile;
import store.StoreManager;
import store.entity.database.MainData;
import store.service.OlapDAO;

/**
 * Builds chart values and value lists from ASH raw data using {@link AshFilter}
 */
@Slf4j
@Singleton
public class FilteredAshLoader {
    private final StoreManager storeManager;
    private final AshFilter ashFilter;

    @Inject
    public FilteredAshLoader(StoreManager storeManager, AshFilter ashFilter) {
        this.storeManager = storeManager;
        this.ashFilter = ashFilter;
    }

    private List<SqlColProfile> getColProfiles() {
        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        return cols == null ? Collections.emptyList() : cols;
    }

    /**
     * Filtered samples grouped like the aggregated data used by Top sql & sessions:
     * id of "sqlId_sessionId_serial" parameter -> (wait event id -> number of samples)
     *
     * @param waitClassId wait class to keep, -1 for all
     */
    public Map<Integer, Map<Integer, Integer>> countBySqlSessionAndWaitEvent(AshFilter.Matcher matcher, IProfile iProfile,
                                                                             long begin, long end, int waitClassId) {
        Map<Integer, Map<Integer, Integer>> out = new HashMap<>();

        List<SqlColProfile> cols = getColProfiles();
        Optional<SqlColProfile> sqlIdCol = findCol(cols, iProfile.getSqlIdColName());
        Optional<SqlColProfile> sessCol = findCol(cols, iProfile.getSessColName());
        Optional<SqlColProfile> serialCol = findCol(cols, iProfile.getSerialColName());
        Optional<SqlColProfile> eventCol = findCol(cols, iProfile.getEventColName());
        Optional<SqlColProfile> waitClassCol = findCol(cols, iProfile.getWaitClassColName());
        if (!sqlIdCol.isPresent() || !sessCol.isPresent() || !serialCol.isPresent()
                || !eventCol.isPresent() || !waitClassCol.isPresent()) {
            return out;
        }

        OlapDAO olapDAO = storeManager.getOlapDAO();
        String cpuLabel = iProfile.getWaitClass((byte) 0);
        Map<String, Integer> paramIdCache = new HashMap<>();
        Map<String, Integer> eventIdCache = new HashMap<>();
        int notFound = 0;

        EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(begin, true, end, true);
        try {
            for (MainData sl : cursor) {
                for (int row = 0; row < sl.getMainMatrix().length; row++) {
                    if (!matcher.matches(sl, row)) {
                        continue;
                    }

                    String waitClass = emptyTo(matcher.getValue(waitClassCol.get(), sl, row), cpuLabel);
                    if (waitClassId != -1 && iProfile.getWaitClassId(waitClass) != waitClassId) {
                        continue;
                    }

                    String sqlId = emptyTo(matcher.getValue(sqlIdCol.get(), sl, row), ConstantManager.NULL_VALUE);
                    String key = sqlId + "_" + matcher.getValue(sessCol.get(), sl, row)
                            + "_" + matcher.getValue(serialCol.get(), sl, row);
                    String event = emptyTo(matcher.getValue(eventCol.get(), sl, row), cpuLabel);

                    int paramId = paramIdCache.computeIfAbsent(key, olapDAO::findParameterId);
                    int eventId = eventIdCache.computeIfAbsent(event, olapDAO::findWaitEventId);
                    if (paramId < 0 || eventId < 0) {
                        notFound++;
                        continue;
                    }

                    out.computeIfAbsent(paramId, k -> new HashMap<>()).merge(eventId, 1, Integer::sum);
                }
            }
        } finally {
            cursor.close();
        }

        if (notFound > 0) {
            log.info("Raw rows without aggregated parameter: " + notFound);
        }

        return out;
    }

    /**
     * Filtered samples in [begin, end) by wait class name -> (event name -> number of samples)
     */
    public Map<String, Map<String, Integer>> countByWaitClassAndEvent(AshFilter.Matcher matcher, IProfile iProfile,
                                                                      long begin, long end) {
        Map<Long, Map<String, Map<String, Integer>>> out = countByBucket(matcher, iProfile, begin, end, end - begin);
        return out.getOrDefault(begin, new HashMap<>());
    }

    /**
     * Filtered samples in [begin, end) grouped in buckets of {@code range} ms starting at {@code begin}:
     * bucket start -> wait class name -> (event name -> number of samples)
     */
    public Map<Long, Map<String, Map<String, Integer>>> countByBucket(AshFilter.Matcher matcher, IProfile iProfile,
                                                                      long begin, long end, long range) {
        Map<Long, Map<String, Map<String, Integer>>> out = new HashMap<>();

        List<SqlColProfile> cols = getColProfiles();
        Optional<SqlColProfile> eventCol = findCol(cols, iProfile.getEventColName());
        Optional<SqlColProfile> waitClassCol = findCol(cols, iProfile.getWaitClassColName());
        if (!eventCol.isPresent() || !waitClassCol.isPresent() || range <= 0) {
            return out;
        }

        String cpuLabel = iProfile.getWaitClass((byte) 0);

        EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(begin, true, end, false);
        try {
            for (MainData sl : cursor) {
                long bucket = begin + ((sl.getKey() - begin) / range) * range;
                for (int row = 0; row < sl.getMainMatrix().length; row++) {
                    if (!matcher.matches(sl, row)) {
                        continue;
                    }
                    String waitClass = emptyTo(matcher.getValue(waitClassCol.get(), sl, row), cpuLabel);
                    String event = emptyTo(matcher.getValue(eventCol.get(), sl, row), cpuLabel);

                    out.computeIfAbsent(bucket, k -> new HashMap<>())
                            .computeIfAbsent(waitClass, k -> new HashMap<>())
                            .merge(event, 1, Integer::sum);
                }
            }
        } finally {
            cursor.close();
        }

        return out;
    }

    /**
     * History "Top activity" chart from filtered raw data (same buckets as OlapDAO#loadDataToCategoryTableXYDatasetRTVHistoryTA)
     */
    public void loadHistoryTA(AshFilter.Matcher matcher, IProfile iProfile, GanttParam ganttParam,
                              CategoryTableXYDatasetRDA dataset, StackChartPanel stackChartPanel) {
        long start = (long) ganttParam.getBeginTime();
        long end = (long) ganttParam.getEndTime();
        double range = (end - start) / ConstantManager.MAX_POINT_PER_GRAPH;
        long step = Math.max(1, Math.round(range));

        Map<Long, Map<String, Map<String, Integer>>> buckets = countByBucket(matcher, iProfile, start, end + step, step);

        LinkedHashSet<String> waitClasses = iProfile.getUniqueTreeEventListByWaitClass();
        waitClasses.forEach(w -> stackChartPanel.getStackedChart().setSeriesPaintDynamicDetail(w));

        for (long d = start; d <= end; d += step) {
            Map<String, Map<String, Integer>> byClass = buckets.getOrDefault(d, Collections.emptyMap());
            for (String w : waitClasses) {
                int sum = byClass.getOrDefault(w, Collections.emptyMap()).values().stream().mapToInt(Integer::intValue).sum();
                dataset.addSeriesValue(d, sum / (range / 1000), w);
            }
        }
    }

    /**
     * History "Detail" charts (one per wait class, series by event) from filtered raw data
     */
    public void loadHistoryDetail(AshFilter.Matcher matcher, IProfile iProfile, GanttParam ganttParam,
                                  Set<NameChartDataset> nameChartDatasetList) {
        long start = (long) ganttParam.getBeginTime();
        long end = (long) ganttParam.getEndTime();
        double range = (end - start) / ConstantManager.MAX_POINT_PER_GRAPH;
        long step = Math.max(1, Math.round(range));

        Map<Long, Map<String, Map<String, Integer>>> buckets = countByBucket(matcher, iProfile, start, end + step, step);

        for (NameChartDataset e : nameChartDatasetList) {
            Set<String> events = new TreeSet<>();
            buckets.values().forEach(byClass -> events.addAll(byClass.getOrDefault(e.getName(), Collections.emptyMap()).keySet()));
            events.forEach(ev -> e.getStackChartPanel().getStackedChart().setSeriesPaintDynamicDetail(ev));

            for (long d = start; d <= end; d += step) {
                Map<String, Integer> byEvent = buckets.getOrDefault(d, Collections.emptyMap())
                        .getOrDefault(e.getName(), Collections.emptyMap());
                for (String ev : events) {
                    e.getDatasetRDA().addSeriesValue(d, byEvent.getOrDefault(ev, 0) / (range / 1000), ev);
                }
            }
        }
    }

    /**
     * Filtered samples by value of a column for each chart point: x -> (value -> number of samples).
     * Each stored sample belongs to the point with the greatest x <= sample time, within {@code range} ms.
     *
     * @param xValues chart points in ascending order
     */
    public Map<Double, Map<String, Integer>> countByValue(AshFilter.Matcher matcher, String colName,
                                                         List<Double> xValues, long range) {
        Map<Double, Map<String, Integer>> out = new HashMap<>();

        Optional<SqlColProfile> col = findCol(getColProfiles(), colName);
        if (!col.isPresent() || xValues.isEmpty()) {
            return out;
        }

        long[] xs = xValues.stream().mapToLong(Double::longValue).toArray();
        long begin = xs[0];
        long end = xs[xs.length - 1] + range;

        EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(begin, true, end, false);
        try {
            for (MainData sl : cursor) {
                int idx = java.util.Arrays.binarySearch(xs, sl.getKey());
                if (idx < 0) {
                    idx = -idx - 2; // greatest x lower than the key
                }
                if (idx < 0 || sl.getKey() >= xs[idx] + range) {
                    continue;
                }
                Map<String, Integer> byValue = out.computeIfAbsent(xValues.get(idx), k -> new HashMap<>());

                for (int row = 0; row < sl.getMainMatrix().length; row++) {
                    if (matcher.matches(sl, row)) {
                        byValue.merge(matcher.getValue(col.get(), sl, row), 1, Integer::sum);
                    }
                }
            }
        } finally {
            cursor.close();
        }

        return out;
    }

    /**
     * Sum of samples by wait class id from {@link #countByWaitClassAndEvent}
     */
    public static long[] toWaitClassArray(Map<String, Map<String, Integer>> byClassEvent, IProfile iProfile, int waitClassCount) {
        long[] out = new long[waitClassCount];
        byClassEvent.forEach((waitClass, events) -> {
            int id = iProfile.getWaitClassId(waitClass);
            if (id >= 0 && id < waitClassCount) {
                out[id] += events.values().stream().mapToInt(Integer::intValue).sum();
            }
        });
        return out;
    }

    private static Optional<SqlColProfile> findCol(List<SqlColProfile> cols, String colName) {
        return cols.stream().filter(c -> c.getColName().equalsIgnoreCase(colName)).findFirst();
    }

    private static String emptyTo(String value, String defaultValue) {
        return value == null || value.isEmpty() || value.equals(Labels.getLabel("local.null")) ? defaultValue : value;
    }

    /**
     * Distinct values with number of samples for a column in [begin, end),
     * keeping the filters of the other columns applied
     */
    public Map<String, Integer> distinctValues(String colName, long begin, long end) {
        Map<String, Integer> out = new HashMap<>();

        List<SqlColProfile> cols = getColProfiles();
        Optional<SqlColProfile> col = cols.stream().filter(c -> c.getColName().equalsIgnoreCase(colName)).findFirst();
        if (!col.isPresent()) {
            return out;
        }

        AshFilter.Matcher matcher = ashFilter.matcher(cols, colName);

        EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(begin, true, end, false);
        try {
            for (MainData sl : cursor) {
                for (int row = 0; row < sl.getMainMatrix().length; row++) {
                    if (matcher.matches(sl, row)) {
                        out.merge(matcher.getValue(col.get(), sl, row), 1, Integer::sum);
                    }
                }
            }
        } finally {
            cursor.close();
        }

        return out;
    }
}
