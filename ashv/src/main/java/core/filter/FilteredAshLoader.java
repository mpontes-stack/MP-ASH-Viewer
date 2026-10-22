package core.filter;

import com.sleepycat.persist.EntityCursor;
import config.Labels;
import config.profile.SqlColProfile;
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
