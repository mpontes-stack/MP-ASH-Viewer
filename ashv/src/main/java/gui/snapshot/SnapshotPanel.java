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
    /**
     * Sessions: who is doing what (locks, rollback, long operations, jobs); resources: is something running out;
     * OS: CPU, memory and I/O of the server
     */
    public enum Page {SESSIONS, RESOURCES, OS}

    private static final List<String> SESSIONS_QUERIES = java.util.Arrays.asList(
            OracleSnapshotQueries.HEADER, OracleSnapshotQueries.SESSIONS_BY_TYPE, OracleSnapshotQueries.SESSIONS_USER,
            OracleSnapshotQueries.LOCKS, OracleSnapshotQueries.ROLLBACK_SESSIONS, OracleSnapshotQueries.ROLLBACK_SMON,
            OracleSnapshotQueries.LONGOPS, OracleSnapshotQueries.JOBS);
    private static final List<String> RESOURCES_QUERIES = java.util.Arrays.asList(
            OracleSnapshotQueries.HEADER, OracleSnapshotQueries.LIMITS, OracleSnapshotQueries.PGA_STAT,
            OracleSnapshotQueries.PGA_LIMIT, OracleSnapshotQueries.PGA_TOP, OracleSnapshotQueries.TEMP_FREE,
            OracleSnapshotQueries.TEMP_TOP, OracleSnapshotQueries.UNDO_STAT, OracleSnapshotQueries.FRA,
            OracleSnapshotQueries.FRA_USAGE, OracleSnapshotQueries.DG_STANDBY, OracleSnapshotQueries.DG_DESTINATIONS,
            OracleSnapshotQueries.TABLESPACES);
    private static final List<String> OS_QUERIES = java.util.Arrays.asList(
            OracleSnapshotQueries.HEADER, OracleSnapshotQueries.OS_STAT, OracleSnapshotQueries.OS_TIME_MODEL,
            OracleSnapshotQueries.OS_MEMORY, OracleSnapshotQueries.OS_IO, OracleSnapshotQueries.OS_CONTAINER,
            OracleSnapshotQueries.OS_PLATFORM, OracleSnapshotQueries.OS_PDB_CPU);
    /** The OS counters are totals since the start of the instance: the first refresh reads twice, this far apart */
    private static final long OS_FIRST_INTERVAL_MS = 3000;
    /** Read while the OS tab is hidden (auto refresh on), for the chart only: one cheap query */
    private static final List<String> OS_CHART_QUERIES = java.util.Collections.singletonList(OracleSnapshotQueries.OS_STAT);

    static final double WARN_PCT = 85;
    static final double CRITICAL_PCT = 95;

    private static final int[] AUTO_SECONDS = {0, 30, 60, 300};
    private static final int AUTO_DEFAULT_INDEX = 2; // 60 s
    private static final long MIN_REFRESH_ON_SHOW_MS = 15000;

    private final SnapshotService service;
    private final boolean isOracle;
    private final Page page;
    private JPanel content;

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
            "gui.snapshot.col.done", "gui.snapshot.col.total.blocks", "gui.snapshot.col.eta", "gui.snapshot.col.pct.done");

    private final JLabel longopsSummary = new JLabel(" ");
    private final DefaultTableModel longopsModel = model("gui.snapshot.col.sid", "gui.snapshot.col.serial",
            "gui.snapshot.col.username", "gui.snapshot.col.operation", "gui.snapshot.col.target", "gui.snapshot.col.elapsed",
            "gui.snapshot.col.remaining", "gui.snapshot.col.sqlid", "gui.snapshot.col.pct.done");

    private final JLabel jobsSummary = new JLabel(" ");
    private final DefaultTableModel jobsModel = model("gui.snapshot.col.owner", "gui.snapshot.col.job",
            "gui.snapshot.col.sid", "gui.snapshot.col.elapsed", "gui.snapshot.col.event", "gui.snapshot.col.sqlid");

    private final JLabel tempSummary = new JLabel(" ");
    private final DefaultTableModel tempModel = model("gui.snapshot.col.sid", "gui.snapshot.col.username",
            "gui.snapshot.col.status", "gui.snapshot.col.program", "gui.snapshot.col.sqlid", "gui.snapshot.col.segtype",
            "gui.snapshot.col.mb");

    private final JLabel undoSummary = new JLabel(" ");

    private final JLabel fraSummary = new JLabel(" ");
    private final DefaultTableModel fraModel = model("gui.snapshot.col.file.type", "gui.snapshot.col.reclaimable",
            "gui.snapshot.col.files", "gui.snapshot.col.pct");

    private final JLabel dgSummary = new JLabel(" ");
    private final DefaultTableModel dgModel = new ReadOnlyModel();
    private JPanel dgSection;

    // previous undo blocks to estimate rollback speed: key -> {blocks, time ms}
    private Map<String, long[]> previousUndo = new HashMap<>();

    private final List<JXTable> tables = new ArrayList<>();
    private List<Object[]> sessionsUserRows = Collections.emptyList();

    private OsView osView;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Timer timer;
    private long lastRefresh = 0;
    private int autoSeconds = 0;

    public SnapshotPanel(RemoteDBManager remoteDBManager, String profileName, Page page) {
        super(new BorderLayout());
        this.page = page;
        this.service = new SnapshotService(remoteDBManager);
        this.isOracle = profileName != null && profileName.toLowerCase().startsWith("oracle");

        this.timer = new Timer(1000, e -> {
            if (!isDisplayable()) { // removed from the window (connection closed): stop reading
                ((Timer) e.getSource()).stop();
                return;
            }
            if (isShowing()) {
                refresh();
            } else if (page == Page.OS) {
                refreshChart(); // the chart keeps its history while the tab is hidden
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
        if (page == Page.OS) {
            refreshChart(); // first point of the chart one interval after connecting
        }
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

        content = new JPanel(new MigLayout("fillx, ins 8, hidemode 3", "[grow, fill, sg col][grow, fill, sg col]", ""));
        headerLbl.setFont(headerLbl.getFont().deriveFont(headerLbl.getFont().getSize2D() + 1f));
        content.add(headerLbl, "span 2, wrap, gapbottom 6");

        if (page == Page.SESSIONS) {
            content.add(section(Labels.getLabel("gui.snapshot.sessions"), sessionsSummary,
                    table(sessionsTypeModel, 4, false), sessionsTop, table(sessionsTopModel, 8, false)), "top");
            content.add(section(Labels.getLabel("gui.snapshot.jobs"), jobsSummary,
                    collapsible(table(jobsModel, 8, false))), "top, wrap");
            content.add(section(Labels.getLabel("gui.snapshot.locks"), locksSummary,
                    collapsible(table(locksModel, 10, false))), "span 2, wrap");
            content.add(section(Labels.getLabel("gui.snapshot.rollback"), rollbackSummary,
                    collapsible(table(rollbackModel, 5, false)), smonSummary,
                    collapsible(table(smonModel, 4, true, true))), "span 2, wrap");
            content.add(section(Labels.getLabel("gui.snapshot.longops"), longopsSummary,
                    collapsible(table(longopsModel, 6, true, true))), "span 2, wrap");
        } else if (page == Page.OS) {
            osView = new OsView(this);
            osView.build(content);
        } else {
            JScrollPane limitsTable = table(limitsModel, 4, true);
            javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            ((JXTable) limitsTable.getViewport().getView()).getColumnExt(3).setCellRenderer(right); // limit: number or UNLIMITED

            content.add(section(Labels.getLabel("gui.snapshot.limits"), limitsSummary, limitsTable), "top");
            undoSummary.setVerticalAlignment(SwingConstants.TOP);
            content.add(section(Labels.getLabel("gui.snapshot.undo"), undoSummary), "top, wrap");
            content.add(section(Labels.getLabel("gui.snapshot.temp"), tempSummary,
                    collapsible(table(tempModel, 5, false))), "top");
            content.add(section(Labels.getLabel("gui.snapshot.fra"), fraSummary,
                    collapsible(table(fraModel, 5, true))), "top, wrap");
            dgSection = section(Labels.getLabel("gui.snapshot.dataguard"), dgSummary, table(dgModel, 4, false));
            content.add(dgSection, "span 2, wrap");
            content.add(section(Labels.getLabel("gui.snapshot.pga"), pgaSummary,
                    table(pgaTopModel, 8, false)), "span 2, wrap");
            content.add(section(Labels.getLabel("gui.snapshot.tablespaces"), tsSummary,
                    table(tsModel, 12, true)), "span 2, wrap");
        }

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private void setAutoRefresh(int index) {
        int seconds = AUTO_SECONDS[Math.max(0, Math.min(index, AUTO_SECONDS.length - 1))];
        autoSeconds = seconds;
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

        // read twice when there is no recent reading (first refresh, or the tab was hidden for a while)
        boolean firstOsReading = page == Page.OS && !osView.hasReading(Math.max(120_000L, 2000L * autoSeconds));
        new SwingWorker<List<Map<String, SnapshotService.Result>>, Void>() {
            @Override
            protected List<Map<String, SnapshotService.Result>> doInBackground() throws Exception {
                List<String> keys = page == Page.SESSIONS ? SESSIONS_QUERIES : page == Page.RESOURCES ? RESOURCES_QUERIES : OS_QUERIES;
                Map<String, String> queries = new java.util.LinkedHashMap<>();
                OracleSnapshotQueries.queries().forEach((k, q) -> {
                    if (keys.contains(k)) {
                        queries.put(k, q);
                    }
                });
                List<Map<String, SnapshotService.Result>> readings = new ArrayList<>();
                readings.add(service.run(queries, OracleSnapshotQueries.views()));
                if (firstOsReading) {
                    Thread.sleep(OS_FIRST_INTERVAL_MS);
                    readings.add(service.run(queries, OracleSnapshotQueries.views()));
                }
                return readings;
            }

            @Override
            protected void done() {
                try {
                    List<Map<String, SnapshotService.Result>> readings = get();
                    if (page == Page.OS) {
                        readings.forEach(osView::add);
                    }
                    render(readings.get(readings.size() - 1));
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

    /**
     * OS tab hidden: read only v$osstat in background for a point of the chart (skipped while a refresh is running)
     */
    private void refreshChart() {
        if (!isOracle || !running.compareAndSet(false, true)) {
            return;
        }
        new SwingWorker<Map<String, SnapshotService.Result>, Void>() {
            @Override
            protected Map<String, SnapshotService.Result> doInBackground() {
                Map<String, String> queries = new java.util.LinkedHashMap<>();
                OracleSnapshotQueries.queries().forEach((k, q) -> {
                    if (OS_CHART_QUERIES.contains(k)) {
                        queries.put(k, q);
                    }
                });
                return service.run(queries, OracleSnapshotQueries.views());
            }

            @Override
            protected void done() {
                try {
                    osView.addChartReading(get());
                } catch (Exception e) {
                    log.error("OS chart reading: " + e.getMessage());
                } finally {
                    running.set(false);
                }
            }
        }.execute();
    }

    private void render(Map<String, SnapshotService.Result> r) {
        renderHeader(r.get(OracleSnapshotQueries.HEADER));
        if (page == Page.SESSIONS) {
            renderSessions(r.get(OracleSnapshotQueries.SESSIONS_BY_TYPE), r.get(OracleSnapshotQueries.SESSIONS_USER));
            renderLocks(r.get(OracleSnapshotQueries.LOCKS));
            renderRollback(r.get(OracleSnapshotQueries.ROLLBACK_SESSIONS), r.get(OracleSnapshotQueries.ROLLBACK_SMON));
            renderLongops(r.get(OracleSnapshotQueries.LONGOPS));
            renderJobs(r.get(OracleSnapshotQueries.JOBS));
        } else if (page == Page.OS) {
            osView.render(r);
        } else {
            renderLimits(r.get(OracleSnapshotQueries.LIMITS));
            renderTemp(r.get(OracleSnapshotQueries.TEMP_FREE), r.get(OracleSnapshotQueries.TEMP_TOP));
            renderUndo(r.get(OracleSnapshotQueries.UNDO_STAT));
            renderFra(r.get(OracleSnapshotQueries.FRA), r.get(OracleSnapshotQueries.FRA_USAGE));
            renderDataGuard(r.get(OracleSnapshotQueries.DG_STANDBY), r.get(OracleSnapshotQueries.DG_DESTINATIONS));
            renderPga(r.get(OracleSnapshotQueries.PGA_STAT), r.get(OracleSnapshotQueries.PGA_LIMIT), r.get(OracleSnapshotQueries.PGA_TOP));
            renderTablespaces(r.get(OracleSnapshotQueries.TABLESPACES));
        }
        tables.forEach(JXTable::packAll);
        fitTables();
    }

    /**
     * Table height follows its rows (up to the configured maximum); collapsible tables are hidden when empty
     */
    private void fitTables() {
        for (TableBox box : tableBoxes) {
            int rows = box.model.getRowCount();
            box.scroll.setVisible(!(box.collapsible && rows == 0));
            int shown = Math.max(box.collapsible ? 1 : 2, Math.min(rows, box.maxRows));
            JXTable t = (JXTable) box.scroll.getViewport().getView();
            int header = t.getTableHeader() == null ? 24 : t.getTableHeader().getPreferredSize().height;
            box.scroll.setPreferredSize(new Dimension(200, t.getRowHeight() * shown + header + 4));
        }
        if (content != null) {
            content.revalidate();
            content.repaint();
        }
    }

    private static class TableBox {
        final DefaultTableModel model;
        final JScrollPane scroll;
        final int maxRows;
        boolean collapsible;

        TableBox(DefaultTableModel model, JScrollPane scroll, int maxRows) {
            this.model = model;
            this.scroll = scroll;
            this.maxRows = maxRows;
        }
    }

    private final List<TableBox> tableBoxes = new ArrayList<>();

    /** Mark a table to be hidden when it has no rows (the summary line says "none") */
    JScrollPane collapsible(JScrollPane scroll) {
        tableBoxes.stream().filter(b -> b.scroll == scroll).forEach(b -> b.collapsible = true);
        scroll.setVisible(false);
        return scroll;
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
            // no peak inside a PDB
            limitsModel.addRow(new Object[]{str(row[0]), current, row[2] == null ? "—" : (Object) num(row[2]), limitText, pct});
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

    /** row: sid, serial#, username, opname, target, sofar, totalwork, pct, elapsed_seconds, time_remaining, sql_id */
    private void renderLongops(SnapshotService.Result r) {
        longopsModel.setRowCount(0);
        if (!r.isOk()) {
            longopsSummary.setText(errorHtml(r));
            return;
        }
        r.rows.forEach(row -> longopsModel.addRow(new Object[]{String.valueOf(num(row[0])), String.valueOf(num(row[1])),
                str(row[2]), str(row[3]), str(row[4]), duration(num(row[8])),
                row[9] == null ? "" : duration(num(row[9])), str(row[10]), dbl(row[7])}));
        longopsSummary.setText(r.rows.isEmpty() ? Labels.getLabel("gui.snapshot.longops.none")
                : "<html><b>" + r.rows.size() + " " + Labels.getLabel("gui.snapshot.longops.running") + "</b></html>");
    }

    /** row: owner, job_name, session_id, elapsed_s, event, sql_id */
    private void renderJobs(SnapshotService.Result r) {
        jobsModel.setRowCount(0);
        if (!r.isOk()) {
            jobsSummary.setText(errorHtml(r));
            return;
        }
        r.rows.forEach(row -> jobsModel.addRow(new Object[]{str(row[0]), str(row[1]),
                row[2] == null ? "" : String.valueOf(num(row[2])), duration(num(row[3])), str(row[4]), str(row[5])}));
        jobsSummary.setText(r.rows.isEmpty() ? Labels.getLabel("gui.snapshot.jobs.none")
                : "<html><b>" + r.rows.size() + " " + Labels.getLabel("gui.snapshot.jobs.running") + "</b></html>");
    }

    /**
     * free row: tablespace_name, tablespace_size, free_space (bytes)
     * top row: sid, serial#, username, status, machine, program, sql_id, tablespace, segtype, mb
     */
    private void renderTemp(SnapshotService.Result free, SnapshotService.Result top) {
        if (!free.isOk()) {
            tempSummary.setText(errorHtml(free));
        } else {
            StringBuilder sb = new StringBuilder("<html>");
            for (Object[] row : free.rows) {
                long size = num(row[1]);
                long used = size - num(row[2]);
                if (sb.length() > 6) {
                    sb.append(" &nbsp;&bull;&nbsp; ");
                }
                sb.append(esc(row[0])).append(": <b>").append(bytes(used)).append("</b> ")
                        .append(Labels.getLabel("gui.snapshot.of")).append(" ").append(bytes(size));
                if (size > 0) {
                    sb.append(" (").append(colorPct(used * 100.0 / size)).append(")");
                }
            }
            tempSummary.setText(sb.append("</html>").toString());
        }

        tempModel.setRowCount(0);
        if (!top.isOk()) {
            if (free.isOk()) {
                tempSummary.setText(errorHtml(top));
            }
            return;
        }
        top.rows.forEach(row -> tempModel.addRow(new Object[]{String.valueOf(num(row[0])), str(row[2]), str(row[3]),
                str(row[5]), str(row[6]), str(row[8]), dbl(row[9])}));
    }

    /**
     * Last v$undostat interval (10 min):
     * begin, end, activeblks, unexpiredblks, expiredblks, tuned_undoretention, maxquerylen, maxqueryid, ssolderrcnt, nospaceerrcnt, block_size
     */
    private void renderUndo(SnapshotService.Result r) {
        if (!r.isOk() || r.rows.isEmpty()) {
            undoSummary.setText(errorHtml(r));
            return;
        }
        Object[] row = r.rows.get(0);
        long blockSize = parseLong(row[10]);
        long ora1555 = num(row[8]);
        long noSpace = num(row[9]);
        String period = row[0] instanceof Date && row[1] instanceof Date
                ? new SimpleDateFormat("HH:mm").format((Date) row[0]) + " - " + new SimpleDateFormat("HH:mm").format((Date) row[1]) : "";

        undoSummary.setText("<html>" + Labels.getLabel("gui.snapshot.undo.period") + " " + period + "<br><br>"
                + Labels.getLabel("gui.snapshot.undo.active") + ": <b>" + bytes(num(row[2]) * blockSize) + "</b><br>"
                + Labels.getLabel("gui.snapshot.undo.unexpired") + ": <b>" + bytes(num(row[3]) * blockSize) + "</b><br>"
                + Labels.getLabel("gui.snapshot.undo.expired") + ": <b>" + bytes(num(row[4]) * blockSize) + "</b><br>"
                + Labels.getLabel("gui.snapshot.undo.retention") + ": <b>" + duration(num(row[5])) + "</b><br>"
                + Labels.getLabel("gui.snapshot.undo.longest") + ": <b>" + duration(num(row[6])) + "</b>"
                + (row[7] == null ? "" : " (SQL_ID " + esc(row[7]) + ")") + "<br>"
                + "ORA-01555 (snapshot too old): " + countHtml(ora1555) + "<br>"
                + Labels.getLabel("gui.snapshot.undo.nospace") + ": " + countHtml(noSpace)
                + "</html>");
    }

    /**
     * dest row: name, space_limit, space_used, space_reclaimable, number_of_files
     * usage row: file_type, percent_space_used, percent_space_reclaimable, number_of_files
     */
    private void renderFra(SnapshotService.Result dest, SnapshotService.Result usage) {
        fraModel.setRowCount(0);
        if (!dest.isOk()) {
            fraSummary.setText(errorHtml(dest));
            return;
        }
        Object[] row = dest.rows.isEmpty() ? null : dest.rows.get(0);
        long limit = row == null ? 0 : num(row[1]);
        if (limit <= 0) {
            fraSummary.setText(Labels.getLabel("gui.snapshot.fra.none"));
            return;
        }
        long used = num(row[2]);
        long reclaimable = num(row[3]);
        fraSummary.setText("<html>" + esc(row[0]) + "<br>" + Labels.getLabel("gui.snapshot.fra.used") + ": <b>" + bytes(used)
                + "</b> " + Labels.getLabel("gui.snapshot.of") + " " + bytes(limit) + " (" + colorPct(used * 100.0 / limit) + ")"
                + " &nbsp;&bull;&nbsp; " + Labels.getLabel("gui.snapshot.fra.reclaimable") + ": <b>" + bytes(reclaimable) + "</b>"
                + " &nbsp;&bull;&nbsp; " + Labels.getLabel("gui.snapshot.fra.real") + ": "
                + colorPct((used - reclaimable) * 100.0 / limit) + "</html>");

        if (usage.isOk()) {
            usage.rows.forEach(u -> fraModel.addRow(new Object[]{str(u[0]), dbl(u[2]), num(u[3]), dbl(u[1])}));
        }
    }

    /**
     * Standby: transport / apply lag (v$dataguard_stats); primary: standby destinations (v$archive_dest_status).
     * Hidden when the database has no Data Guard.
     */
    private void renderDataGuard(SnapshotService.Result standby, SnapshotService.Result destinations) {
        boolean hasStandbyStats = standby.isOk() && !standby.rows.isEmpty();
        boolean hasDestinations = destinations.isOk() && !destinations.rows.isEmpty();
        boolean permissionError = (!standby.isOk() && standby.isNoPermission())
                || (!destinations.isOk() && destinations.isNoPermission());

        dgSection.setVisible(hasStandbyStats || hasDestinations || permissionError);
        dgModel.setRowCount(0);

        if (hasStandbyStats) {
            dgModel.setColumnIdentifiers(new Object[]{Labels.getLabel("gui.snapshot.col.metric"),
                    Labels.getLabel("gui.snapshot.col.value"), Labels.getLabel("gui.snapshot.col.computed")});
            StringBuilder sb = new StringBuilder("<html>Standby &nbsp;&bull;&nbsp; ");
            for (Object[] row : standby.rows) {
                dgModel.addRow(new Object[]{str(row[0]), str(row[1]), str(row[2])});
                if (!"apply finish time".equals(str(row[0]))) {
                    long lag = intervalSeconds(str(row[1]));
                    String color = lag >= 900 ? hex(criticalColor()) : lag >= 300 ? hex(warnColor()) : null;
                    sb.append(esc(row[0])).append(": <b>")
                            .append(color == null ? duration(lag) : "<font color='" + color + "'>" + duration(lag) + "</font>")
                            .append("</b> &nbsp;&nbsp;");
                }
            }
            dgSummary.setText(sb.append("</html>").toString());
        } else if (hasDestinations) {
            dgModel.setColumnIdentifiers(new Object[]{"DEST_ID", "DEST_NAME", "STATUS", "TYPE", "DATABASE_MODE",
                    "RECOVERY_MODE", "GAP_STATUS", "ERROR"});
            int problems = 0;
            for (Object[] row : destinations.rows) {
                dgModel.addRow(row);
                String status = str(row[2]);
                String gap = str(row[6]);
                if (!"VALID".equals(status) || (!gap.isEmpty() && !"NO GAP".equals(gap)) || !str(row[7]).isEmpty()) {
                    problems++;
                }
            }
            dgSummary.setText("<html>" + Labels.getLabel("gui.snapshot.dg.primary") + " " + destinations.rows.size()
                    + " " + Labels.getLabel("gui.snapshot.dg.destinations") + " &nbsp;&bull;&nbsp; "
                    + (problems == 0 ? "<b>" + Labels.getLabel("gui.snapshot.dg.ok") + "</b>"
                    : "<b><font color='" + hex(criticalColor()) + "'>" + problems + " "
                    + Labels.getLabel("gui.snapshot.dg.problems") + "</font></b>") + "</html>");
        } else if (permissionError) {
            dgSummary.setText(errorHtml(standby.isOk() ? destinations : standby));
        }
    }

    /** "+00 00:05:12" (day to second interval text) -> seconds */
    private static long intervalSeconds(String text) {
        try {
            String t = text.trim().replace("+", "");
            String[] dayTime = t.split(" ");
            String[] hms = dayTime[dayTime.length - 1].split(":");
            long days = dayTime.length > 1 ? Long.parseLong(dayTime[0]) : 0;
            return days * 86400 + Long.parseLong(hms[0]) * 3600 + Long.parseLong(hms[1]) * 60
                    + Math.round(Double.parseDouble(hms[2]));
        } catch (Exception e) {
            return 0;
        }
    }

    /** seconds -> "2d 3h", "1h 05min", "4min 10s", "12s" */
    private static String duration(long seconds) {
        if (seconds >= 86400) {
            return seconds / 86400 + "d " + (seconds % 86400) / 3600 + "h";
        } else if (seconds >= 3600) {
            return seconds / 3600 + "h " + String.format("%02d", (seconds % 3600) / 60) + "min";
        } else if (seconds >= 60) {
            return seconds / 60 + "min " + seconds % 60 + "s";
        }
        return seconds + "s";
    }

    private static String countHtml(long count) {
        return count > 0 ? "<b><font color='" + hex(criticalColor()) + "'>" + count + "</font></b>" : "<b>0</b>";
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

    JPanel section(String title, JLabel summary, JComponent... body) {
        JPanel panel = new JPanel(new MigLayout("fillx, ins 4, hidemode 3", "[grow, fill]", ""));
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
        return table(model, visibleRows, hasPctColumn, false);
    }

    /** Table whose last column is a share (always the neutral blue: a big share is not an alert) */
    JScrollPane shareTable(DefaultTableModel model, int visibleRows) {
        JScrollPane scroll = table(model, visibleRows, false, false);
        ((JXTable) scroll.getViewport().getView()).getColumnExt(model.getColumnCount() - 1)
                .setCellRenderer(new PercentBarRenderer(false, true));
        return scroll;
    }

    /**
     * @param isProgress last column is a progress (neutral color) instead of an occupation (85% / 95% alert colors)
     */
    private JScrollPane table(DefaultTableModel model, int visibleRows, boolean hasPctColumn, boolean isProgress) {
        JXTable table = new JXTable(model);
        table.setEditable(false);
        table.setColumnControlVisible(false);
        table.setHorizontalScrollEnabled(true);
        if (hasPctColumn) {
            table.getColumnExt(model.getColumnCount() - 1).setCellRenderer(new PercentBarRenderer(isProgress));
        }
        tables.add(table);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(200, table.getRowHeight() * visibleRows + 28));
        tableBoxes.add(new TableBox(model, scroll, visibleRows));
        return scroll;
    }

    static DefaultTableModel model(String... labelKeys) {
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
        private final boolean isProgress;
        private final boolean isShare;
        private Double pct;
        private boolean selected;
        private JTable table;

        PercentBarRenderer(boolean isProgress) {
            this(isProgress, false);
        }

        PercentBarRenderer(boolean isProgress, boolean isShare) {
            this.isProgress = isProgress;
            this.isShare = isShare;
        }

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
            g.setColor(isProgress ? progressColor() : isShare ? okColor()
                    : pct >= CRITICAL_PCT ? criticalColor() : pct >= WARN_PCT ? warnColor() : okColor());
            g.fillRoundRect(2, 4, barWidth, getHeight() - 8, 4, 4);

            g.setColor(selected ? table.getSelectionForeground() : table.getForeground());
            int textWidth = g.getFontMetrics().stringWidth(text);
            g.drawString(text, getWidth() - textWidth - 4, (getHeight() + g.getFontMetrics().getAscent()) / 2 - 2);
        }
    }

    /* ---------- formatting ---------- */

    static Color okColor() { return new Color(76, 145, 210); }
    static Color progressColor() { return new Color(90, 170, 120); }
    static Color warnColor() { return new Color(230, 165, 40); }
    static Color criticalColor() { return new Color(220, 70, 60); }

    static Color secondaryColor() {
        Color c = UIManager.getColor("Label.disabledForeground");
        return c != null ? c : Color.GRAY;
    }

    static String hex(Color c) {
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

    String errorHtml(SnapshotService.Result r) {
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

    static String esc(Object o) {
        return str(o).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
