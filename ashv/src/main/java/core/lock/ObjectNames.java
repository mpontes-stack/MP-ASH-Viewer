package core.lock;

import core.snapshot.SnapshotService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import remote.RemoteDBManager;

/**
 * Names of CURRENT_OBJ# (OWNER.NAME or OWNER.NAME:PARTITION), looked up in dba_objects only for the ids shown
 * and cached for the connection. Oracle only; without a connection the ids stay as numbers.
 */
@Slf4j
public class ObjectNames {
    private static final int IDS_PER_QUERY = 500;
    private static final String QUERY = "SELECT object_id, owner, object_name, subobject_name FROM %s WHERE object_id IN (%s)";

    private final RemoteDBManager remoteDBManager;
    private final boolean oracle;
    private final SnapshotService service;

    /** id -> name, "" for an id not found (dropped object) */
    private final Map<String, String> names = new ConcurrentHashMap<>();
    private volatile String view = "dba_objects";

    public ObjectNames(RemoteDBManager remoteDBManager, String profileName) {
        this.remoteDBManager = remoteDBManager;
        this.oracle = profileName != null && profileName.startsWith("Oracle");
        this.service = new SnapshotService(remoteDBManager);
    }

    /** Name of an object id, null when not looked up yet or not found */
    public String get(String id) {
        String name = names.get(id);
        return name == null || name.isEmpty() ? null : name;
    }

    /** Ids still to look up */
    public List<String> missing(Collection<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (isObjectId(id) && !names.containsKey(id) && !out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    public boolean isAvailable() {
        return oracle && remoteDBManager.getBasicDataSource() != null;
    }

    /**
     * Look up the ids in the database (call off the EDT)
     *
     * @return null when done, otherwise the error
     */
    public String lookup(List<String> ids) {
        if (ids.isEmpty() || !isAvailable()) {
            return null;
        }
        for (int from = 0; from < ids.size(); from += IDS_PER_QUERY) {
            List<String> chunk = ids.subList(from, Math.min(ids.size(), from + IDS_PER_QUERY));
            SnapshotService.Result result = query(chunk);
            if (!result.isOk() && result.error.startsWith("sem permissão") && view.equals("dba_objects")) {
                view = "all_objects"; // objects visible to the user
                result = query(chunk);
            }
            if (!result.isOk()) {
                return result.error;
            }
            chunk.forEach(id -> names.putIfAbsent(id, ""));
            for (Object[] row : result.rows) {
                String name = row[1] + "." + row[2] + (row[3] == null ? "" : ":" + row[3]);
                names.put(String.valueOf(row[0]), name);
            }
        }
        return null;
    }

    private SnapshotService.Result query(List<String> ids) {
        String sql = String.format(QUERY, view, String.join(",", ids));
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("objects", sql);
        return service.run(queries, Collections.singletonMap("objects", view)).get("objects");
    }

    /** Only positive numbers are sent to the database */
    private static boolean isObjectId(String id) {
        if (id == null || id.isEmpty() || id.length() > 12 || id.equals("0")) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            if (!Character.isDigit(id.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
