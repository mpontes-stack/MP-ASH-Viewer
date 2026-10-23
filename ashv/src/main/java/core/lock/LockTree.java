package core.lock;

import com.sleepycat.persist.EntityCursor;
import config.profile.SqlColProfile;
import core.filter.AshFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import lombok.Getter;
import profile.IProfile;
import store.StoreManager;
import store.entity.database.MainData;

/**
 * Lock tree of a period built from ASH raw data: final blocker -> sessions waiting for it -> sessions waiting for those...
 * <p>
 * The final blocker of each waiting row is found by following BLOCKING_SESSION inside the same sample
 * (an inactive blocker is not sampled, so the chain ends on it); FINAL_BLOCKING_SESSION is not used,
 * v$session leaves it empty in most samples. Waiting rows must match the active filters;
 * blocker details come from all rows.
 */
public final class LockTree {
    public static final String BLOCKING_SESSION = "BLOCKING_SESSION";

    /** Raw data columns shown in the tree (optional, empty when not collected) */
    public static final String USERNAME = "USERNAME", PROGRAM = "PROGRAM", MACHINE = "MACHINE", MODULE = "MODULE",
            CURRENT_OBJ = "CURRENT_OBJ#", SESSION_TYPE = "SESSION_TYPE",
            BLOCKING_SESSION_SERIAL = "BLOCKING_SESSION_SERIAL#";

    /** The connection does not collect BLOCKING_SESSION (e.g. Postgres) */
    @Getter private boolean notCollected;
    /** Final blockers, most waits caused first */
    @Getter private final List<Node> roots = new ArrayList<>();

    /**
     * Final blockers to show: background blockers (e.g. LGWR for "log file sync") are not locks of the application
     */
    public List<Node> getRoots(boolean includeBackground) {
        List<Node> out = new ArrayList<>();
        roots.stream().filter(r -> includeBackground || !r.isBackground()).forEach(out::add);
        return out;
    }

    /** Distinct sessions waiting below the given nodes */
    public static int blockedSessions(List<Node> nodes) {
        Set<String> sids = new HashSet<>();
        nodes.forEach(n -> collect(n, sids));
        return sids.size();
    }

    private static void collect(Node node, Set<String> sids) {
        for (Node child : node.children) {
            sids.add(child.sid);
            collect(child, sids);
        }
    }

    /** Session in the tree: a final blocker (root) or a session waiting for its parent */
    public static final class Node {
        @Getter private final String sid;
        @Getter private String serial = "";
        @Getter private String username = "", program = "", machine = "", module = "";
        private final Map<String, Integer> sqlIds = new HashMap<>();
        private final Map<String, Integer> events = new HashMap<>();
        private final Map<String, Integer> objects = new HashMap<>();

        /** Samples waiting for the parent (0 for a root) */
        @Getter private int waitSamples;
        /** Waits (samples) of all sessions below */
        @Getter private int causedSamples;
        /** Distinct sessions below */
        @Getter private int affected;
        @Getter private long first = Long.MAX_VALUE;
        @Getter private long last = Long.MIN_VALUE;

        /** Root only: samples with sessions waiting for it / of those, samples where it was active (sampled) */
        @Getter private int blockingSamples;
        @Getter private int activeWhileBlocking;
        @Getter private boolean root;
        /** Sampled as a BACKGROUND session (LGWR, DBWR...) */
        @Getter private boolean background;

        @Getter private final List<Node> children = new ArrayList<>();

        private Node(String sid) {
            this.sid = sid;
        }

        public String getSqlId() { return top(sqlIds); }
        public String getEvent() { return top(events); }
        public String getObject() { return top(objects); }

        private void add(Row r, long time) {
            if (serial.isEmpty()) serial = r.serial;
            if (username.isEmpty()) username = r.username;
            if (program.isEmpty()) program = r.program;
            if (machine.isEmpty()) machine = r.machine;
            if (module.isEmpty()) module = r.module;
            count(sqlIds, r.sqlId);
            count(events, r.event);
            count(objects, r.object);
            first = Math.min(first, time);
            last = Math.max(last, time);
        }

        private void info(Row r) {
            if ("BACKGROUND".equalsIgnoreCase(r.type)) background = true;
            if (serial.isEmpty()) serial = r.serial;
            if (username.isEmpty()) username = r.username;
            if (program.isEmpty()) program = r.program;
            if (machine.isEmpty()) machine = r.machine;
            if (module.isEmpty()) module = r.module;
        }

        private Node copy() {
            Node n = new Node(sid);
            n.serial = serial;
            n.username = username;
            n.program = program;
            n.machine = machine;
            n.module = module;
            n.sqlIds.putAll(sqlIds);
            n.events.putAll(events);
            n.objects.putAll(objects);
            n.waitSamples = waitSamples;
            n.first = first;
            n.last = last;
            return n;
        }

        private static void count(Map<String, Integer> map, String value) {
            if (!value.isEmpty()) map.merge(value, 1, Integer::sum);
        }

        private static String top(Map<String, Integer> map) {
            return map.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        }
    }

    /** Values of one stored row used by the tree */
    private static final class Row {
        String sid, serial, blocker, blockerSerial, username, program, machine, module, sqlId, event, object, type;
        boolean matches;
    }

    private LockTree() {}

    /**
     * @param matcher current filters (also reads the values)
     * @param stale   true when the result is no longer needed (stops reading)
     * @return null when stopped by {@code stale}
     */
    public static LockTree load(StoreManager storeManager, IProfile iProfile, AshFilter.Matcher matcher,
                                long begin, long end, BooleanSupplier stale) {
        LockTree tree = new LockTree();

        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        if (cols == null) cols = Collections.emptyList();
        Optional<SqlColProfile> blockerCol = findCol(cols, BLOCKING_SESSION);
        Optional<SqlColProfile> sidCol = findCol(cols, iProfile.getSessColName());
        if (!blockerCol.isPresent() || !sidCol.isPresent()) {
            tree.notCollected = true;
            return tree;
        }
        Optional<SqlColProfile> serialCol = findCol(cols, iProfile.getSerialColName());
        Optional<SqlColProfile> sqlIdCol = findCol(cols, iProfile.getSqlIdColName());
        Optional<SqlColProfile> eventCol = findCol(cols, iProfile.getEventColName());
        Optional<SqlColProfile> userCol = findCol(cols, USERNAME);
        Optional<SqlColProfile> programCol = findCol(cols, PROGRAM);
        Optional<SqlColProfile> machineCol = findCol(cols, MACHINE);
        Optional<SqlColProfile> moduleCol = findCol(cols, MODULE);
        Optional<SqlColProfile> objectCol = findCol(cols, CURRENT_OBJ);
        Optional<SqlColProfile> typeCol = findCol(cols, SESSION_TYPE);
        Optional<SqlColProfile> blockerSerialCol = findCol(cols, BLOCKING_SESSION_SERIAL);

        // final blocker -> blocker -> waiter -> waits of the waiter for this blocker
        Map<String, Map<String, Map<String, Node>>> edges = new HashMap<>();
        Map<String, Node> rootStats = new HashMap<>();
        // session details by "sid" and by "sid,serial"
        Map<String, Node> sessionInfo = new HashMap<>();
        // final blocker -> serial# of the blocker seen by its waiters -> samples (collected since stage 2)
        Map<String, Map<String, Integer>> rootSerials = new HashMap<>();

        EntityCursor<MainData> cursor = storeManager.getDatabaseDAO().getMainDataDAO().getPrimaryIndex()
                .entities(begin, true, end, false);
        try {
            for (MainData sl : cursor) {
                if (stale.getAsBoolean()) {
                    return null;
                }
                int rowCount = sl.getMainMatrix().length;
                Row[] rows = new Row[rowCount];
                Map<String, Row> bySid = new HashMap<>();
                for (int i = 0; i < rowCount; i++) {
                    Row r = new Row();
                    r.sid = value(matcher, sidCol, sl, i);
                    r.blocker = value(matcher, blockerCol, sl, i);
                    r.serial = value(matcher, serialCol, sl, i);
                    r.username = value(matcher, userCol, sl, i);
                    r.program = value(matcher, programCol, sl, i);
                    r.machine = value(matcher, machineCol, sl, i);
                    r.module = value(matcher, moduleCol, sl, i);
                    r.sqlId = value(matcher, sqlIdCol, sl, i);
                    r.event = value(matcher, eventCol, sl, i);
                    r.object = value(matcher, objectCol, sl, i);
                    r.type = value(matcher, typeCol, sl, i);
                    r.blockerSerial = value(matcher, blockerSerialCol, sl, i);
                    r.matches = matcher.matches(sl, i);
                    rows[i] = r;
                    bySid.putIfAbsent(r.sid, r);
                    sessionInfo.computeIfAbsent(r.sid, Node::new).info(r);
                    sessionInfo.computeIfAbsent(r.sid + "," + r.serial, k -> new Node(r.sid)).info(r);
                }

                Set<String> rootsOfSample = new HashSet<>();
                for (Row r : rows) {
                    if (r.blocker.isEmpty()) {
                        continue;
                    }
                    String root = finalBlocker(r, bySid);
                    rootsOfSample.add(root);
                    if (root.equals(r.blocker) && !r.blockerSerial.isEmpty()) {
                        Node.count(rootSerials.computeIfAbsent(root, k -> new HashMap<>()), r.blockerSerial);
                    }
                    if (r.matches) {
                        Node edge = edges.computeIfAbsent(root, k -> new HashMap<>())
                                .computeIfAbsent(r.blocker, k -> new HashMap<>())
                                .computeIfAbsent(r.sid, Node::new);
                        edge.waitSamples++;
                        edge.add(r, sl.getKey());
                    }
                }

                for (String root : rootsOfSample) {
                    Node stats = rootStats.computeIfAbsent(root, Node::new);
                    stats.blockingSamples++;
                    Row rootRow = bySid.get(root);
                    if (rootRow != null) {
                        stats.activeWhileBlocking++;
                        stats.add(rootRow, sl.getKey());
                    }
                }
            }
        } finally {
            cursor.close();
        }

        edges.forEach((rootSid, byBlocker) -> {
            Node root = rootStats.get(rootSid).copy();
            root.root = true;
            root.blockingSamples = rootStats.get(rootSid).blockingSamples;
            root.activeWhileBlocking = rootStats.get(rootSid).activeWhileBlocking;
            // serial# from its waiters, or from its own samples while blocking: details of that session only;
            // without it (history before stage 2), details of any session with the same SID in the period
            Map<String, Integer> serials = rootSerials.get(rootSid);
            if (serials != null) {
                root.serial = Node.top(serials);
            }
            Node info = root.serial.isEmpty() ? sessionInfo.get(rootSid) : sessionInfo.get(rootSid + "," + root.serial);
            if (info != null) {
                root.info(toRow(info));
                root.background = info.background;
            }
            root.first = Long.MAX_VALUE;
            root.last = Long.MIN_VALUE;

            Set<String> path = new HashSet<>();
            path.add(rootSid);
            addChildren(root, byBlocker, path);
            tree.roots.add(root);
        });
        tree.roots.sort(Comparator.comparingInt(Node::getCausedSamples).reversed());
        return tree;
    }

    /** Children of a node from the waits of one final blocker; returns the sessions below */
    private static Set<String> addChildren(Node node, Map<String, Map<String, Node>> byBlocker, Set<String> path) {
        Set<String> below = new LinkedHashSet<>();
        Map<String, Node> waiters = byBlocker.getOrDefault(node.sid, Collections.emptyMap());
        for (Node edge : waiters.values()) {
            if (path.contains(edge.sid)) {
                continue; // deadlock: the session is already above
            }
            Node child = edge.copy();
            path.add(child.sid);
            below.add(child.sid);
            below.addAll(addChildren(child, byBlocker, path));
            path.remove(child.sid);

            node.children.add(child);
            node.causedSamples += child.waitSamples + child.causedSamples;
            node.first = Math.min(node.first, child.first);
            node.last = Math.max(node.last, child.last);
        }
        node.children.sort(Comparator.comparingInt((Node n) -> n.waitSamples + n.causedSamples).reversed());
        node.affected = below.size();
        return below;
    }

    /** Session at the end of the blocking chain of a row, inside the same sample */
    private static String finalBlocker(Row r, Map<String, Row> bySid) {
        Set<String> seen = new HashSet<>();
        seen.add(r.sid);
        String current = r.blocker;
        while (true) {
            Row next = bySid.get(current);
            if (next == null || next.blocker.isEmpty() || !seen.add(current)) {
                return current;
            }
            current = next.blocker;
        }
    }

    private static Row toRow(Node info) {
        Row r = new Row();
        r.serial = ""; // never taken from another session with the same SID
        r.username = info.username;
        r.program = info.program;
        r.machine = info.machine;
        r.module = info.module;
        return r;
    }

    private static String value(AshFilter.Matcher matcher, Optional<SqlColProfile> col, MainData sl, int row) {
        if (!col.isPresent()) {
            return "";
        }
        String value = matcher.getValue(col.get(), sl, row);
        return value == null || value.equals("null") ? "" : value.trim();
    }

    private static Optional<SqlColProfile> findCol(List<SqlColProfile> cols, String colName) {
        return cols.stream().filter(c -> c.getColName().equalsIgnoreCase(colName)).findFirst();
    }
}
