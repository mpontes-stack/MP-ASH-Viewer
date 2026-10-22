package core.filter;

import com.sleepycat.persist.EntityCursor;
import config.Labels;
import config.profile.SqlColProfile;
import core.manager.ConstantManager;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
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
     * Number of filtered samples by wait class id in [begin, end)
     */
    public long[] countByWaitClass(AshFilter.Matcher matcher, IProfile iProfile,
                                   long begin, long end, int waitClassCount) {
        long[] out = new long[waitClassCount];

        Optional<SqlColProfile> waitClassCol = getColProfiles().stream()
                .filter(c -> c.getColName().equalsIgnoreCase(iProfile.getWaitClassColName())).findFirst();
        if (!waitClassCol.isPresent()) {
            return out;
        }

        String cpuLabel = iProfile.getWaitClass((byte) 0);
        String nullLabel = Labels.getLabel("local.null");

        EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(begin, true, end, false);
        try {
            for (MainData sl : cursor) {
                for (int row = 0; row < sl.getMainMatrix().length; row++) {
                    if (!matcher.matches(sl, row)) {
                        continue;
                    }
                    String waitClass = matcher.getValue(waitClassCol.get(), sl, row);
                    if (waitClass == null || waitClass.isEmpty() || waitClass.equals(nullLabel)) {
                        waitClass = cpuLabel;
                    }
                    int id = iProfile.getWaitClassId(waitClass);
                    if (id >= 0 && id < waitClassCount) {
                        out[id]++;
                    }
                }
            }
        } finally {
            cursor.close();
        }

        return out;
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
