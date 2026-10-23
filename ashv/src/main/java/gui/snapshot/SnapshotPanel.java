package gui.snapshot;

import config.Labels;
import core.snapshot.OracleSnapshotQueries;
import core.snapshot.SnapshotService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.HierarchyEvent;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jdesktop.swingx.JXTable;
import remote.RemoteDBManager;

/**
 * "Agora" tab: point in time view of the database (sessions, limits, PGA, tablespaces), no local history
 */
@Slf4j
public class SnapshotPanel extends JPanel {
    static final double WARN_PCT = 85;
    static final double CRITICAL_PCT = 95;

    private static final int[] AUTO_SECONDS = {0, 30, 60, 300};
    private static final int AUTO_DEFAULT_INDEX = 2; // 60 s
    private static final long MIN_REFRESH_ON_SHOW_MS = 15000;

    private final SnapshotService service;
    private final boolean isOracle;

    private final JButton refreshButton = new JButton(Labels.getLabel("gui.snapshot.refresh"));
    private final JComboBox<String> autoBox = new JComboBox<>();
    private final JLabel statusLbl = new JLabel(" ");
    private final JLabel headerLbl = new JLabel(" ");

    private final JLabel sessionsSummary = new JLabel(" ");
    private final DefaultTableModel sessionsTypeModel = model("gui.snapshot.col.type", "gui.snapshot.col.active",
            "gui.snapshot.col.inactive", "gui.snapshot.col.other", "gui.snapshot.col.total");
    private final JComboBox<String> sessionsGroupBox = new JComboBox<>();
    private final DefaultTableModel sessionsTopModel = model("gui.snapshot.col.value", "gui.snapshot.col.active",
            "gui.snapshot.col.inactive", "gui.snapshot.col.total");

    private final JLabel limitsSummary = new JLabel(" ");
    private final DefaultTableModel limitsModel = model("gui.snapshot.col.resource", "gui.snapshot.col.current",
            "gui.snapshot.col.max", "gui.snapshot.col.limit", "gui.snapshot.col.pct");

    private final JLabel pgaSummary = new JLabel(" ");
    private final DefaultTableModel pgaTopModel = new ReadOnlyModel();

    private final JLabel tsSummary = new JLabel(" ");
    private final DefaultTableModel tsModel = model("gui.snapshot.col.tablespace", "gui.snapshot.col.contents",
            "gui.snapshot.col.used.gb", "gui.snapshot.col.max.gb", "gui.snapshot.col.pct");

    private final JLabel locksSummary = new JLabel(" ");
    private final DefaultTableModel locksModel = model("gui.snapshot.col.sid", "gui.snapshot.col.serial",
            "gui.snapshot.col.username", "gui.snapshot.col.status", "gui.snapshot.col.machine", "gui.snapshot.col.program",
            "gui.snapshot.col.sqlid", "gui.snapshot.col.event", "gui.snapshot.col.wait", "gui.snapshot.col.object",
            "gui.snapshot.col.blocked.by");

    private final JLabel rollbackSummary = new JLabel(" ");
    private final DefaultTableModel rollbackModel = model("gui.snapshot.col.sid", "gui.snapshot.col.serial",
            "gui.snapshot.col.username", "gui.snapshot.col.status", "gui.snapshot.col.machine", "gui.snapshot.col.program",
            "gui.snapshot.col.sqlid", "gui.snapshot.col.start", "gui.snapshot.col.undo.blocks", "gui.snapshot.col.undo.mb",
            "gui.snapshot.col.rate", "gui.snapshot.col.eta");
    private final JLabel smonSummary = new JLabel(" ");
    private final DefaultTableModel smonModel = model("gui.snapshot.col.usn", "gui.snapshot.col.state",
            "gui.snapshot.col.done", "gui.snapshot.col.total.blocks", "gui.snapshot.col.eta", "gui.snapshot.col.pct");

    // previous undo blocks to estimate rollback speed: key -> {blocks, time ms}
    private Map<String, long[]> previousUndo = new HashMap<>();

    private final List<JXTable> tables = new ArrayList<>();
    private List<Object[]> sessionsUserRows = Collections.emptyList();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Timer timer;
    private long lastRefresh = 0;

    public SnapshotPanel(RemoteDBManager remoteDBManager, String profileName) {
        super(new BorderLayout());
        this.service = new SnapshotService(remoteDBManager);
        this.isOracle = profileName != null && profileName.toLowerCase().startsWith("oracle");

        this.timer = new Timer(1000, e -> {
            if (isShowing()) {
                refresh();
            }
        });

        if (!isOracle) {
            JLabel lbl = new JLabel(Labels.getLabel("gui.snapshot.not.available"), SwingConstants.CENTER);
            add(lbl, BorderLayout.CENTER);
            return;
        }

        add(createToolbar(), BorderLayout.NORTH);
        add(createContent(), BorderLayout.CENTER);

        // refresh when the tab is opened (unless just refreshed)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && System.currentTimeMillis() - lastRefresh > MIN_REFRESH_ON_SHOW_MS) {
                refresh();
            }
        });

        setAutoRefresh(AUTO_DEFAULT_INDEX);
    }

    private JPanel createToolbar() {
        refreshButton.setToolTipText(Labels.getLabel("gui.snapshot.refresh.tooltip"));
        refreshButton.addActionListener(e -> refresh());

        autoBox.addItem(Labels.getLabel("gui.snapshot.auto.off"));
        autoBox.addItem("30 s");
        autoBox.addItem("60 s");
        autoBox.addItem("5 min");
        autoBox.setSelectedIndex(AUTO_DEFAULT_INDEX);
        autoBox.setToolTipText(Labels.getLabel("gui.snapshot.auto.tooltip"));
        autoBox.addActionListener(e -> setAutoRefresh(autoBox.getSelectedIndex()));

        statusLbl.setForeground(secondaryColor());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        toolbar.add(refreshButton);
        toolbar.add(new JLabel(Labels.getLabel("gui.snapshot.auto")));
        toolbar.add(autoBox);
        toolbar.add(statusLbl);
        return toolbar;
    }

    private JComponent createContent() {
        sessionsGroupBox.addItem("USERNAME");
        sessionsGroupBox.addItem("MACHINE");
        sessionsGroupBox.addItem("PROGRAM");
        sessionsGroupBox.addItem("MODULE");
        sessionsGroupBox.addActionListener(e -> renderSessionsTop());

        JPanel sessionsTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sessionsTop.add(new JLabel(Labels.getLabel("gui.snapshot.sessions.group")));
        sessionsTop.add(sessionsGroupBox);

        JPanel content = new JPanel(new MigLayout("fillx, ins 8", "[grow, fill, sg col][grow, fill, sg col]", ""));
        headerLbl.setFont(headerLbl.getFont().deriveFont(headerLbl.getFont().getSize2D() + 1f));
        content.add(headerLbl, "span 2, wrap, gapbottom 6");

        JScrollPane limitsTable = table(limitsModel, 5, true);
        javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        ((JXTable) limitsTable.getViewport().getView()).getColumnExt(3).setCellRenderer(right); // limit: number or UNLIMITED

        content.add(section(Labels.getLabel("gui.snapshot.sessions"), sessionsSummary,
                table(sessionsTypeModel, 4, false), sessionsTop, table(sessionsTopModel, 10, false)), "top");
        content.add(section(Labels.getLabel("gui.snapshot.limits"), limitsSummary, limitsTable), "top, wrap");
        content.add(section(Labels.getLabel("gui.snapshot.locks"), locksSummary,
                table(locksModel, 6, false)), "span 2, wrap");
        content.add(section(Labels.getLabel("gui.snapshot.rollback"), rollbackSummary,
                table(rollbackModel, 4, false), smonSummary, table(smonModel, 3, true)), "span 2, wrap");
        content.add(section(Labels.getLabel("gui.snapshot.pga"), pgaSummary,
                table(pgaTopModel, 12, false)), "span 2, wrap");
        content.add(section(Labels.getLabel("gui.snapshot.tablespaces"), tsSummary,
                table(tsModel, 12, true)), "span 2, wrap");

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void setAutoRefresh(int index) {
        int seconds = AUTO_SECONDS[Math.max(0, Math.min(index, AUTO_SECONDS.length - 1))];
        timer.stop();
        if (seconds > 0) {
            timer.setDelay(seconds * 1000);
            timer.setInitialDelay(seconds * 1000);
            timer.start();
        }
    }

    /**
     * Run all queries in background and update the blocks (ignored while a refresh is running)
     */
    public void refresh() {
        if (!isOracle || !running.compareAndSet(false, true)) {
            return;
        }
        refreshButton.setEnabled(false);
        statusLbl.setText(Labels.getLabel("gui.snapshot.refreshing"));
        long start = System.currentTimeMillis();

        new SwingWorker<Map<String, SnapshotService.Result>, Void>() {
            @Override
            protected Map<String, SnapshotService.Result> doInBackground() {
                return service.run(OracleSnapshotQueries.queries(), OracleSnapshotQueries.views());
            }

            @Override
            protected void done() {
                try {
                    render(get());
                    statusLbl.setText(Labels.getLabel("gui.snapshot.updated") + " "
                            + new SimpleDateFormat("HH:mm:ss").format(new Date())
                            + " (" + (System.currentTimeMillis() - start) + " ms)");
                } catch (Exception e) {
                    log.error("Snapshot refresh: " + e.getMessage());
                    statusLbl.setText(e.getMessage());
                } finally {
                    lastRefresh = System.currentTimeMillis();
                    running.set(false);
                    refreshButton.setEnabled(true);
                }
            }
        }.execute();
    }

    private void render(Map<String, SnapshotService.Result> r) {
        renderHeader(r.get(OracleSnapshotQueries.HEADER));
        renderSessions(r.get(OracleSnapshotQueries.SESSIONS_BY_TYPE), r.get(OracleSnapshotQueries.SESSIONS_USER));
        renderLimits(r.get(OracleSnapshotQueries.LIMITS));
        renderLocks(r.get(OracleSnapshotQueries.LOCKS));
        renderRollback(r.get(OracleSnapshotQueries.ROLLBACK_SESSIONS), r.get(OracleSnapshotQueries.ROLLBACK_SMON));
        renderPga(r.get(OracleSnapshotQueries.PGA_STAT), r.get(OracleSnapshotQueries.PGA_LIMIT), r.get(OracleSnapshotQueries.PGA_TOP));
        renderTablespaces(r.get(OracleSnapshotQueries.TABLESPACES));
        tables.forEach(JXTable::packAll);
    }

    private void renderHeader(SnapshotService.Result r) {
        if (!r.isOk() || r.rows.isEmpty()) {
            headerLbl.setText(errorHtml(r));
            return;
        }
        Object[] row = r.rows.get(0); // instance, host, version, startup, status, name, role, open mode
        String uptime = "";
        if (row[3] instanceof Date) {
            Duration d = Duration.ofMillis(System.currentTimeMillis() - ((Date) row[3]).getTime());
            uptime = " (" + d.toDays() + "d " + d.toHours() % 24 + "h)";
        }
        headerLbl.setText("<html><b>" + esc(row[5]) + "</b> &nbsp;" + esc(row[0]) + " @ " + esc(row[1])
                + " &nbsp;|&nbsp; Oracle " + esc(row[2])
                + " &nbsp;|&nbsp; " + esc(row[6]) + " / " + esc(row[7])
                + " &nbsp;|&nbsp; " + Labels.getLabel("gui.snapshot.up.since") + " "
                + (row[3] instanceof Date ? new SimpleDateFormat("dd/MM/yyyy HH:mm").format((Date) row[3]) : esc(row[3]))
                + uptime + "</html>");
    }

    private void renderSessions(SnapshotService.Result byType, SnapshotService.Result user) {
        sessionsTypeModel.setRowCount(0);
        if (!byType.isOk()) {
            sessionsSummary.setText(errorHtml(byType));
        } else {
            Map<String, long[]> types = new java.util.TreeMap<>(); // type -> active, inactive, other
            for (Object[] row : byType.rows) {
                long[] c = types.computeIfAbsent(str(row[0]), k -> new long[3]);
                String status = str(row[1]);
                c["ACTIVE".equals(status) ? 0 : "INACTIVE".equals(status) ? 1 : 2] += num(row[2]);
            }
            long[] total = new long[3];
            types.forEach((type, c) -> {
                sessionsTypeModel.addRow(new Object[]{type, c[0], c[1], c[2], c[0] + c[1] + c[2]});
                for (int i = 0; i < 3; i++) total[i] += c[i];
            });
            sessionsTypeModel.addRow(new Object[]{Labels.getLabel("gui.snapshot.col.total"), total[0], total[1], total[2],
                    total[0] + total[1] + total[2]});
            sessionsSummary.setText("<html>" + Labels.getLabel("gui.snapshot.col.total") + ": <b>" + fmt(total[0] + total[1] + total[2])
                    + "</b> &nbsp;&bull;&nbsp; " + Labels.getLabel("gui.snapshot.col.active") + ": <b>" + fmt(total[0])
                    + "</b> &nbsp;&bull;&nbsp; " + Labels.getLabel("gui.snapshot.col.inactive") + ": <b>" + fmt(total[1]) + "</b></html>");
        }

        sessionsUserRows = user.isOk() ? user.rows : Collections.emptyList();
        renderSessionsTop();
    }

    /** User sessions grouped by the column chosen in the combo (computed locally) */
    private void renderSessionsTop() {
        int col = Math.max(0, sessionsGroupBox.getSelectedIndex()); // username, machine, program, module
        Map<String, long[]> groups = new HashMap<>();
        for (Object[] row : sessionsUserRows) {
            String key = row[col] == null ? Labels.getLabel("gui.filter.empty") : str(row[col]);
            long[] c = groups.computeIfAbsent(key, k -> new long[2]);
            c["ACTIVE".equals(str(row[4])) ? 0 : 1] += num(row[5]); // status, count
        }
        sessionsTopModel.setRowCount(0);
        groups.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]))
                .limit(20)
                .forEach(e -> sessionsTopModel.addRow(new Object[]{e.getKey(), e.getValue()[0], e.getValue()[1],
                        e.getValue()[0] + e.getValue()[1]}));
        tables.forEach(JXTable::packAll);
    }

    private void renderLimits(SnapshotService.Result r) {
        limitsModel.setRowCount(0);
        if (!r.isOk()) {
            limitsSummary.setText(errorHtml(r));
            return;
        }
        double worst = 0;
        String worstName = "";
        for (Object[] row : r.rows) {
            long current = num(row[1]);
            String limit = str(row[3]).trim();
            Double pct = null;
            try {
                long l = Long.parseLong(limit);
                if (l > 0) {
                    pct = current * 100.0 / l;
                }
            } catch (NumberFormatException ignore) {
                // UNLIMITED
            }
            if (pct != null && pct > worst) {
                worst = pct;
                worstName = str(row[0]);
            }
            String limitText = limit;
            try {
                limitText = fmt(Long.parseLong(limit));
            } catch (NumberFormatException ignore) {
                // UNLIMITED
            }
            limitsModel.addRow(new Object[]{str(row[0]), current, num(row[2]), limitText, pct});
        }
        limitsSummary.setText(worst == 0 ? " " : "<html>" + Labels.getLabel("gui.snapshot.limits.worst") + " <b>"
                + esc(worstName) + "</b>: " + colorPct(worst) + "</html>");
    }

    /**
     * Blocking tree: root blockers first, blocked sessions below them (indented)
     * row: sid, serial#, username, status, machine, program, sql_id, blocking_session, final_blocking, event, seconds_in_wait, obj
     */
    private void renderLocks(SnapshotService.Result r) {
        locksModel.setRowCount(0);
        if (!r.isOk()) {
            locksSummary.setText(errorHtml(r));
            return;
        }
        if (r.rows.isEmpty()) {
            locksSummary.setText(Labels.getLabel("gui.snapshot.locks.none"));
            return;
        }

        Map<Long, Object[]> bySid = new java.util.LinkedHashMap<>();
        r.rows.forEach(row -> bySid.put(num(row[0]), row));
        Map<Long, List<Long>> children = new HashMap<>();
        List<Long> roots = new ArrayList<>();
        bySid.forEach((sid, row) -> {
            Long blocker = row[7] == null ? null : num(row[7]);
            if (blocker == null || !bySid.containsKey(blocker)) {
                roots.add(sid);
            } else {
                children.computeIfAbsent(blocker, k -> new ArrayList<>()).add(sid);
            }
        });
        roots.sort((a, b) -> Integer.compare(countDescendants(b, children), countDescendants(a, children)));

        java.util.Set<Long> visited = new java.util.HashSet<>();
        for (Long root : roots) {
            addLockRow(root, 0, bySid, children, visited);
        }

        long blocked = bySid.values().stream().filter(row -> row[7] != null).count();
        long blockers = roots.stream().filter(children::containsKey).count();
        Object[] longest = bySid.values().stream().filter(row -> row[7] != null)
                .max(java.util.Comparator.comparingLong(row -> num(row[10]))).orElse(null);

        locksSummary.setText("<html><b><font color='" + hex(criticalColor()) + "'>" + blocked + " "
                + Labels.getLabel("gui.snapshot.locks.blocked") + " " + blockers + " "
                + Labels.getLabel("gui.snapshot.locks.blockers") + "</font></b>"
                + (longest == null ? "" : " &nbsp;&bull;&nbsp; " + Labels.getLabel("gui.snapshot.locks.longest") + " <b>"
                + fmt(num(longest[10])) + " s</b> (SID " + num(longest[0]) + ")")
                + "</html>");
    }

    private void addLockRow(Long sid, int level, Map<Long, Object[]> bySid, Map<Long, List<Long>> children,
                            java.util.Set<Long> visited) {
        if (!visited.add(sid)) {
            return; // deadlock cycle
        }
        Object[] row = bySid.get(sid);
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < level; i++) {
            indent.append(i == level - 1 ? "   \u2514 " : "      ");
        }
        String obj = str(row[11]);
        locksModel.addRow(new Object[]{indent + String.valueOf(sid), String.valueOf(num(row[1])), str(row[2]), str(row[3]),
                str(row[4]), str(row[5]), str(row[6]), str(row[9]), num(row[10]), ".".equals(obj) ? "" : obj,
                row[7] == null ? "" : String.valueOf(num(row[7]))});
        children.getOrDefault(sid, Collections.emptyList())
                .forEach(child -> addLockRow(child, level + 1, bySid, children, visited));
    }

    private static int countDescendants(Long sid, Map<Long, List<Long>> children) {
        int n = 0;
        java.util.Deque<Long> stack = new java.util.ArrayDeque<>(children.getOrDefault(sid, Collections.emptyList()));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        while (!stack.isEmpty()) {
            Long s = stack.pop();
            if (seen.add(s)) {
                n++;
                stack.addAll(children.getOrDefault(s, Collections.emptyList()));
            }
        }
        return n;
    }

    /**
     * Sessions rolling back and dead transactions recovered by SMON; speed and estimate from the previous refresh
     * session row: sid, serial#, username, status, machine, program, sql_id, start_time, used_ublk, used_urec, undo_mb
     * smon row: usn, state, undoblocksdone, undoblockstotal, pct
     */
    private void renderRollback(SnapshotService.Result sessions, SnapshotService.Result smon) {
        long now = System.currentTimeMillis();
        Map<String, long[]> current = new HashMap<>();

        rollbackModel.setRowCount(0);
        if (!sessions.isOk()) {
            rollbackSummary.setText(errorHtml(sessions));
        } else {
            for (Object[] row : sessions.rows) {
                String key = "S" + num(row[0]) + "," + num(row[1]);
                long blocks = num(row[8]);
                current.put(key, new long[]{blocks, now});
                double rate = rate(previousUndo.get(key), blocks, now, false);
                rollbackModel.addRow(new Object[]{String.valueOf(num(row[0])), String.valueOf(num(row[1])), str(row[2]),
                        str(row[3]), str(row[4]), str(row[5]), str(row[6]), str(row[7]), blocks, dbl(row[10]),
                        rate > 0 ? NumberFormat.getIntegerInstance().format(rate) + " blk/s" : "",
                        eta(rate, blocks)});
            }
            rollbackSummary.setText(sessions.rows.isEmpty() ? Labels.getLabel("gui.snapshot.rollback.none")
                    : "<html><b><font color='" + hex(warnColor()) + "'>" + sessions.rows.size() + " "
                    + Labels.getLabel("gui.snapshot.rollback.sessions") + "</font></b></html>");
        }

        smonModel.setRowCount(0);
        if (!smon.isOk()) {
            smonSummary.setText(errorHtml(smon));
        } else {
            for (Object[] row : smon.rows) {
                String key = "U" + num(row[0]);
                long done = num(row[2]);
                long total = num(row[3]);
                current.put(key, new long[]{done, now});
                double rate = rate(previousUndo.get(key), done, now, true);
                smonModel.addRow(new Object[]{num(row[0]), str(row[1]), done, total, eta(rate, total - done), dbl(row[4])});
            }
            smonSummary.setText(smon.rows.isEmpty() ? Labels.getLabel("gui.snapshot.smon.none")
                    : "<html><b><font color='" + hex(warnColor()) + "'>" + smon.rows.size() + " "
                    + Labels.getLabel("gui.snapshot.smon.transactions") + "</font></b></html>");
        }

        previousUndo = current;
    }

    /**
     * Blocks per second since the previous refresh (undo blocks decrease for sessions, "done" increases for SMON)
     */
    private static double rate(long[] previous, long value, long now, boolean increasing) {
        if (previous == null || now <= previous[1]) {
            return 0;
        }
        long diff = increasing ? value - previous[0] : previous[0] - value;
        return diff > 0 ? diff / ((now - previous[1]) / 1000.0) : 0;
    }

    private static String eta(double rate, long remaining) {
        if (rate <= 0) {
            return Labels.getLabel("gui.snapshot.eta.wait");
        }
        long seconds = Math.round(remaining / rate);
        return seconds >= 3600 ? "~" + seconds / 3600 + " h " + (seconds % 3600) / 60 + " min"
                : seconds >= 60 ? "~" + seconds / 60 + " min " + seconds % 60 + " s" : "~" + seconds + " s";
    }

    private void renderPga(SnapshotService.Result stat, SnapshotService.Result limit, SnapshotService.Result top) {
        if (!stat.isOk()) {
            pgaSummary.setText(errorHtml(stat));
        } else {
            Map<String, Long> v = new HashMap<>();
            stat.rows.forEach(row -> v.put(str(row[0]), num(row[1])));
            long target = v.getOrDefault("aggregate PGA target parameter", 0L);
            long allocated = v.getOrDefault("total PGA allocated", 0L);
            long limitBytes = limit.isOk() && !limit.rows.isEmpty() ? parseLong(limit.rows.get(0)[0]) : 0;
            long overAllocation = v.getOrDefault("over allocation count", 0L);

            StringBuilder sb = new StringBuilder("<html>");
            sb.append("Target: <b>").append(bytes(target)).append("</b>");
            if (limitBytes > 0) {
                sb.append(" &nbsp;&bull;&nbsp; Limit: <b>").append(bytes(limitBytes)).append("</b>");
            }
            sb.append(" &nbsp;&bull;&nbsp; ").append(Labels.getLabel("gui.snapshot.pga.allocated")).append(": <b>")
                    .append(bytes(allocated)).append("</b>");
            if (target > 0) {
                sb.append(" (").append(colorPct(allocated * 100.0 / target)).append(" ")
                        .append(Labels.getLabel("gui.snapshot.pga.of.target")).append(")");
            }
            sb.append(" &nbsp;&bull;&nbsp; ").append(Labels.getLabel("gui.snapshot.pga.inuse")).append(": <b>")
                    .append(bytes(v.getOrDefault("total PGA inuse", 0L))).append("</b>");
            sb.append(" &nbsp;&bull;&nbsp; ").append(Labels.getLabel("gui.snapshot.pga.max")).append(": <b>")
                    .append(bytes(v.getOrDefault("maximum PGA allocated", 0L))).append("</b>");
            sb.append(" &nbsp;&bull;&nbsp; Freeable: <b>").append(bytes(v.getOrDefault("total freeable PGA memory", 0L))).append("</b>");
            sb.append(" &nbsp;&bull;&nbsp; Over allocation count: ")
                    .append(overAllocation > 0 ? "<b><font color='" + hex(criticalColor()) + "'>" + fmt(overAllocation) + "</font></b>"
                            : "<b>0</b>");
            pgaSummary.setText(sb.append("</html>").toString());
        }

        if (!top.isOk()) {
            pgaTopModel.setColumnIdentifiers(new Object[]{""});
            pgaTopModel.setRowCount(0);
            if (stat.isOk()) {
                pgaSummary.setText(errorHtml(top));
            }
        } else {
            pgaTopModel.setColumnIdentifiers(top.columns.toArray());
            pgaTopModel.setRowCount(0);
            top.rows.forEach(row -> {
                Object[] out = row.clone();
                for (int i = 0; i < Math.min(2, out.length); i++) { // SID, SERIAL#: identifiers, no grouping
                    out[i] = out[i] == null ? "" : String.valueOf(num(out[i]));
                }
                pgaTopModel.addRow(out);
            });
        }
    }

    private void renderTablespaces(SnapshotService.Result r) {
        tsModel.setRowCount(0);
        if (!r.isOk()) {
            tsSummary.setText(errorHtml(r));
            return;
        }
        int critical = 0, warn = 0;
        for (Object[] row : r.rows) {
            double pct = row[4] instanceof Number ? ((Number) row[4]).doubleValue() : 0;
            if (pct >= CRITICAL_PCT) {
                critical++;
            } else if (pct >= WARN_PCT) {
                warn++;
            }
            tsModel.addRow(new Object[]{str(row[0]), str(row[1]), dbl(row[2]), dbl(row[3]), pct});
        }
        if (critical + warn == 0) {
            tsSummary.setText(Labels.getLabel("gui.snapshot.ts.ok"));
        } else {
            tsSummary.setText("<html>"
                    + (critical > 0 ? "<b><font color='" + hex(criticalColor()) + "'>" + critical + " "
                    + Labels.getLabel("gui.snapshot.ts.critical") + "</font></b>" : "")
                    + (critical > 0 && warn > 0 ? " &nbsp;&bull;&nbsp; " : "")
                    + (warn > 0 ? "<b><font color='" + hex(warnColor()) + "'>" + warn + " "
                    + Labels.getLabel("gui.snapshot.ts.warn") + "</font></b>" : "")
                    + "</html>");
        }
    }

    /* ---------- components ---------- */

    private JPanel section(String title, JLabel summary, JComponent... body) {
        JPanel panel = new JPanel(new MigLayout("fillx, ins 4", "[grow, fill]", ""));
        JLabel titleLbl = new JLabel(title);
        titleLbl.setFont(titleLbl.getFont().deriveFont(Font.BOLD));
        titleLbl.setForeground(UIManager.getColor("Component.accentColor") != null
                ? UIManager.getColor("Component.accentColor") : titleLbl.getForeground());
        panel.add(titleLbl, "split 2, gapbottom 2");
        panel.add(new JSeparator(), "growx, wrap");
        panel.add(summary, "wrap");
        for (JComponent c : body) {
            panel.add(c, "wrap");
        }
        return panel;
    }

    private JScrollPane table(DefaultTableModel model, int visibleRows, boolean hasPctColumn) {
        JXTable table = new JXTable(model);
        table.setEditable(false);
        table.setColumnControlVisible(false);
        table.setHorizontalScrollEnabled(true);
        if (hasPctColumn) {
            table.getColumnExt(model.getColumnCount() - 1).setCellRenderer(new PercentBarRenderer());
        }
        tables.add(table);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(200, table.getRowHeight() * visibleRows + 28));
        return scroll;
    }

    private static DefaultTableModel model(String... labelKeys) {
        DefaultTableModel m = new ReadOnlyModel();
        Object[] cols = new Object[labelKeys.length];
        for (int i = 0; i < labelKeys.length; i++) {
            cols[i] = Labels.getLabel(labelKeys[i]);
        }
        m.setColumnIdentifiers(cols);
        return m;
    }

    private static class ReadOnlyModel extends DefaultTableModel {
        @Override public boolean isCellEditable(int row, int column) { return false; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            for (int r = 0; r < getRowCount(); r++) {
                Object v = getValueAt(r, columnIndex);
                if (v != null) {
                    return v instanceof Number ? Number.class : Object.class;
                }
            }
            return Object.class;
        }
    }

    /**
     * Percentage with a horizontal bar: accent color, yellow from 85%, red from 95%
     */
    private static class PercentBarRenderer extends JComponent implements TableCellRenderer {
        private Double pct;
        private boolean selected;
        private JTable table;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            this.table = table;
            this.pct = value instanceof Number ? ((Number) value).doubleValue() : null;
            this.selected = isSelected;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(selected ? table.getSelectionBackground() : table.getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (pct == null) {
                return;
            }
            String text = NumberFormat.getNumberInstance().format(Math.round(pct * 10) / 10.0) + "%";
            g.setFont(table.getFont());
            int textSpace = g.getFontMetrics().stringWidth("100,0%") + 8;

            // bar on the left, value on the right (never over the bar)
            int barArea = Math.max(10, getWidth() - textSpace - 4);
            int barWidth = (int) Math.round(barArea * Math.min(100, Math.max(0, pct)) / 100.0);
            g.setColor(new Color(128, 128, 128, 40));
            g.fillRoundRect(2, 4, barArea, getHeight() - 8, 4, 4);
            g.setColor(pct >= CRITICAL_PCT ? criticalColor() : pct >= WARN_PCT ? warnColor() : okColor());
            g.fillRoundRect(2, 4, barWidth, getHeight() - 8, 4, 4);

            g.setColor(selected ? table.getSelectionForeground() : table.getForeground());
            int textWidth = g.getFontMetrics().stringWidth(text);
            g.drawString(text, getWidth() - textWidth - 4, (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
        }
    }

    /* ---------- formatting ---------- */

    static Color okColor() { return new Color(76, 145, 210); }
    static Color warnColor() { return new Color(230, 165, 40); }
    static Color criticalColor() { return new Color(220, 70, 60); }

    private static Color secondaryColor() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    private static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String colorPct(double pct) {
        String text = NumberFormat.getNumberInstance().format(Math.round(pct * 10) / 10.0) + "%";
        if (pct >= CRITICAL_PCT) {
            return "<b><font color='" + hex(criticalColor()) + "'>" + text + "</font></b>";
        } else if (pct >= WARN_PCT) {
            return "<b><font color='" + hex(warnColor()) + "'>" + text + "</font></b>";
        }
        return "<b>" + text + "</b>";
    }

    private String errorHtml(SnapshotService.Result r) {
        String msg = r == null ? "" : r.isOk() ? Labels.getLabel("gui.snapshot.no.rows") : r.error;
        return "<html><font color='" + hex(warnColor()) + "'>⚠ " + esc(msg) + "</font></html>";
    }

    private static String bytes(long b) {
        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(1);
        if (b >= 1L << 30) {
            nf.setMaximumFractionDigits(2);
            return nf.format(b / (double) (1L << 30)) + " GB";
        }
        return nf.format(b / (double) (1L << 20)) + " MB";
    }

    private static String fmt(long n) {
        return NumberFormat.getIntegerInstance().format(n);
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : parseLong(o);
    }

    private static Double dbl(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : null;
    }

    private static long parseLong(Object o) {
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String esc(Object o) {
        return str(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
