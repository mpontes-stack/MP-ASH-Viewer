package core.filter;

import config.profile.SqlColProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import store.ConvertManager;
import store.entity.database.MainData;

/**
 * Cumulative filters applied to ASH raw data.
 * Conditions on different columns are combined with AND,
 * values of the same column are combined with OR.
 * Exclusions (column != values) are stored with the key "!COLUMN" and keep rows matching none of the values.
 */
@Slf4j
@Singleton
public class AshFilter {
    private final ConvertManager convertManager;

    private final LinkedHashMap<String, LinkedHashSet<String>> conditions = new LinkedHashMap<>();
    /** "Hide background" next to the charts: not a condition of the filter bar (Clear and favorites keep it) */
    private volatile boolean hideBackground;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    @Inject
    public AshFilter(ConvertManager convertManager) {
        this.convertManager = convertManager;
    }

    private static final String EXCLUSION_PREFIX = "!";

    /** Condition key of an exclusion (column != values) */
    public static String exclusionKey(String colName) {
        return EXCLUSION_PREFIX + colName;
    }

    public static boolean isExclusion(String key) {
        return key.startsWith(EXCLUSION_PREFIX);
    }

    /** Column name of a condition key */
    public static String colOf(String key) {
        return isExclusion(key) ? key.substring(EXCLUSION_PREFIX.length()) : key;
    }

    /** Oracle background processes: SESSION_TYPE of v$session (USER / BACKGROUND) and of ASH (FOREGROUND / BACKGROUND) */
    public static final String SESSION_TYPE = "SESSION_TYPE";
    public static final String BACKGROUND = "BACKGROUND";

    public synchronized boolean isActive() {
        return !conditions.isEmpty() || hideBackground;
    }

    public boolean isHideBackground() {
        return hideBackground;
    }

    /** Initial value when a connection opens, before the charts are loaded (no change notification) */
    public void initHideBackground(boolean hide) {
        hideBackground = hide;
    }

    /** Hide the rows of background processes in every chart and table that follows the filter */
    public void setHideBackground(boolean hide) {
        if (hideBackground != hide) {
            hideBackground = hide;
            fireChanged();
        }
    }

    public synchronized Map<String, Set<String>> getConditions() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        conditions.forEach((k, v) -> out.put(k, Collections.unmodifiableSet(new LinkedHashSet<>(v))));
        return out;
    }

    public void setCondition(String colName, Set<String> values) {
        synchronized (this) {
            if (values == null || values.isEmpty()) {
                conditions.remove(colName);
            } else {
                conditions.put(colName, new LinkedHashSet<>(values));
            }
        }
        fireChanged();
    }

    /**
     * Replace all conditions at once (single change notification)
     */
    public void replaceAll(Map<String, ? extends Set<String>> newConditions) {
        synchronized (this) {
            conditions.clear();
            newConditions.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    conditions.put(k, new LinkedHashSet<>(v));
                }
            });
        }
        fireChanged();
    }

    public void removeCondition(String colName) {
        synchronized (this) {
            conditions.remove(colName);
        }
        fireChanged();
    }

    public void clear() {
        synchronized (this) {
            conditions.clear();
        }
        fireChanged();
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        listeners.forEach(Runnable::run);
    }

    /**
     * Matcher for the current conditions, optionally ignoring one column
     * (used to list the values of a column while the other filters stay applied).
     */
    public Matcher matcher(List<SqlColProfile> colProfiles, String ignoreColName) {
        Map<String, Set<String>> current = withBackground(getConditions());
        if (ignoreColName != null) {
            current.remove(ignoreColName);
            current.remove(exclusionKey(ignoreColName));
        }
        return new Matcher(colProfiles, current);
    }

    public Matcher matcher(List<SqlColProfile> colProfiles) {
        return matcher(colProfiles, null);
    }

    /**
     * Matcher for the current conditions plus others (column -> values) that only this task uses;
     * an extra condition on a filtered column replaces it
     */
    public Matcher matcherWith(List<SqlColProfile> colProfiles, Map<String, Set<String>> extra) {
        Map<String, Set<String>> current = withBackground(getConditions());
        current.putAll(extra);
        return new Matcher(colProfiles, current);
    }

    /** The conditions plus SESSION_TYPE != BACKGROUND when "Hide background" is on */
    private Map<String, Set<String>> withBackground(Map<String, Set<String>> current) {
        if (hideBackground) {
            Set<String> excluded = new LinkedHashSet<>(current.getOrDefault(exclusionKey(SESSION_TYPE), Collections.emptySet()));
            excluded.add(BACKGROUND);
            current.put(exclusionKey(SESSION_TYPE), excluded);
        }
        return current;
    }

    /**
     * Evaluates conditions against stored raw rows. Not thread safe, create one per task.
     */
    public class Matcher {
        private final List<SqlColProfile> filterCols = new ArrayList<>();
        private final List<Set<String>> filterValues = new ArrayList<>();
        private final List<Boolean> filterExclusions = new ArrayList<>();
        private final Map<Integer, Map<Integer, String>> valueCache = new HashMap<>();

        private Matcher(List<SqlColProfile> colProfiles, Map<String, Set<String>> current) {
            current.forEach((key, values) -> {
                String colName = colOf(key);
                Optional<SqlColProfile> col = colProfiles.stream()
                        .filter(c -> c.getColName().equalsIgnoreCase(colName)).findFirst();
                if (!col.isPresent()) {
                    log.info("Filter column not found: " + colName);
                }
                filterCols.add(col.orElse(null));
                filterValues.add(values);
                filterExclusions.add(isExclusion(key));
            });
        }

        public boolean isEmpty() {
            return filterCols.isEmpty();
        }

        public boolean matches(MainData sl, int row) {
            for (int i = 0; i < filterCols.size(); i++) {
                SqlColProfile col = filterCols.get(i);
                if (filterExclusions.get(i)) {
                    // column not collected: nothing to exclude
                    if (col != null && filterValues.get(i).contains(getValue(col, sl, row))) {
                        return false;
                    }
                } else if (col == null || !filterValues.get(i).contains(getValue(col, sl, row))) {
                    // column not collected: nothing can match
                    return false;
                }
            }
            return true;
        }

        /**
         * Value of a cell as shown in the Raw data table (cached per stored id)
         */
        public String getValue(SqlColProfile col, MainData sl, int row) {
            int[] rowData = sl.getMainMatrix()[row];
            if (col.getColId() > rowData.length) {
                return String.valueOf(convertManager.getMatrixDataForJTable(col.getColDbTypeName(), col.getColId(), sl, row));
            }

            int intVal = rowData[col.getColId() - 1];
            return valueCache
                    .computeIfAbsent(col.getColId(), k -> new HashMap<>())
                    .computeIfAbsent(intVal, k -> String.valueOf(
                            convertManager.getMatrixDataForJTable(col.getColDbTypeName(), col.getColId(), sl, row)));
        }
    }
}
