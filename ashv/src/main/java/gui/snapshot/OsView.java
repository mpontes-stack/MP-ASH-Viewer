package gui.snapshot;

import config.Labels;
import core.snapshot.OracleSnapshotQueries;
import core.snapshot.SnapshotService;
import gui.util.Themes;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;

/**
 * "Now: OS" page: CPU, load and memory of the server (v$osstat: the whole host, also inside a PDB), the CPU of this
 * database (v$sys_time_model), its I/O by function (v$iostat_function) and, in the root of a CDB, the CPU of each
 * container. The counters are totals since the start of the instance: every value is the difference between the
 * last two readings.
 */
class OsView {
    /** The chart shows the last hour (one point per refresh, also while the tab is hidden) */
    private static final long CHART_WINDOW_MS = 3600_000L;
    private static final double GB = 1024.0 * 1024 * 1024;
    /** Paging from this rate on is shown as a warning */
    private static final double PAGING_WARN_MBS = 1;
    private static final int HINT_WIDTH = 420;

    private final SnapshotPanel panel;

    private final JLabel scopeLbl = new JLabel(" ");
    private final JLabel cpuSummary = new JLabel(" ");
    private final JLabel memSummary = new JLabel(" ");
    private final DefaultTableModel memModel = SnapshotPanel.model("gui.os.col.memory", "gui.os.col.gb", "gui.os.col.pct.ram");
    private final JLabel ioSummary = new JLabel(" ");
    private final DefaultTableModel ioModel = SnapshotPanel.model("gui.os.col.function", "gui.os.col.read.mbs",
            "gui.os.col.write.mbs", "gui.os.col.iops", "gui.os.col.latency", "gui.os.col.pct.io");
    private final JLabel pdbSummary = new JLabel(" ");
    private final DefaultTableModel pdbModel = SnapshotPanel.model("gui.os.col.container", "gui.os.col.cpus",
            "gui.os.col.pct.dbcpu");
    private JPanel pdbSection;
    private final Chart chart = new Chart();

    // full readings (tab shown): the differences of the CPU, memory, PDB and I/O blocks
    private Reading previous;
    private Reading last;
    // last reading of the chart: full readings and the v$osstat only readings taken while the tab is hidden
    private Reading chartLast;
    private final List<Point> points = new ArrayList<>();

    OsView(SnapshotPanel panel) {
        this.panel = panel;
    }

    void build(JPanel content) {
        cpuSummary.setVerticalAlignment(SwingConstants.TOP);
        content.add(scopeLbl, "span 2, wrap, gapbottom 4");
        content.add(panel.section(Labels.getLabel("gui.os.cpu"), cpuSummary), "top");
        content.add(panel.section(Labels.getLabel("gui.os.memory"), memSummary, panel.shareTable(memModel, 6)), "top, wrap");
        content.add(panel.section(Labels.getLabel("gui.os.chart"), new JLabel(Labels.getLabel("gui.os.chart.hint")), chart),
                "span 2, wrap");
        pdbSection = panel.section(Labels.getLabel("gui.os.pdb"), pdbSummary, panel.shareTable(pdbModel, 8));
        pdbSection.setVisible(false);
        content.add(pdbSection, "span 2, wrap");
        content.add(panel.section(Labels.getLabel("gui.os.io"), ioSummary,
                panel.collapsible(panel.shareTable(ioModel, 8))), "span 2, wrap");
    }

    /** A full reading not older than maxAgeMs (an older one would average the blocks over a long time) */
    boolean hasReading(long maxAgeMs) {
        return last != null && System.currentTimeMillis() - last.time <= maxAgeMs;
    }

    /** A new full reading: the previous one is kept for the differences (EDT) */
    void add(Map<String, SnapshotService.Result> r) {
        Reading reading = Reading.of(r);
        if (reading == null) {
            return;
        }
        if (last != null && reading.before(last)) { // instance restarted: counters back to zero
            last = null;
        }
        previous = last;
        last = reading;
        addChartPoint(reading);
    }

    /** A v$osstat only reading taken while the tab is hidden: a point of the chart (EDT) */
    void addChartReading(Map<String, SnapshotService.Result> r) {
        Reading reading = Reading.of(r);
        if (reading != null) {
            addChartPoint(reading);
            chart.repaint();
        }
    }

    private void addChartPoint(Reading reading) {
        if (chartLast != null && reading.before(chartLast)) {
            chartLast = null;
            points.clear();
        }
        if (chartLast != null) {
            Interval i = new Interval(chartLast, reading);
            if (i.total > 0) {
                points.add(new Point(reading.time, i.pct("USER_TIME"), i.pct("SYS_TIME"), i.pct("IOWAIT_TIME"),
                        i.pct("IDLE_TIME"), reading.loadPerCore()));
                while (!points.isEmpty() && reading.time - points.get(0).time > CHART_WINDOW_MS) {
                    points.remove(0);
                }
            }
        }
        chartLast = reading;
    }

    void render(Map<String, SnapshotService.Result> r) {
        SnapshotService.Result os = r.get(OracleSnapshotQueries.OS_STAT);
        if (os == null || !os.isOk()) {
            cpuSummary.setText(panel.errorHtml(os));
            memSummary.setText(" ");
            memModel.setRowCount(0);
            return;
        }
        renderScope(r);
        if (previous == null || last == null) {
            cpuSummary.setText(Labels.getLabel("gui.os.waiting"));
            return;
        }
        Interval i = new Interval(previous, last);
        renderCpu(i, r.get(OracleSnapshotQueries.OS_TIME_MODEL));
        renderMemory(i, r.get(OracleSnapshotQueries.OS_MEMORY));
        renderPdbs(i, r.get(OracleSnapshotQueries.OS_PDB_CPU));
        renderIo(i, r.get(OracleSnapshotQueries.OS_IO));
        chart.repaint();
    }

    /* ---------- blocks ---------- */

    private void renderScope(Map<String, SnapshotService.Result> r) {
        SnapshotService.Result platform = r.get(OracleSnapshotQueries.OS_PLATFORM);
        String platformName = platform != null && platform.isOk() && !platform.rows.isEmpty()
                ? SnapshotPanel.esc(platform.rows.get(0)[0]) + " &nbsp;|&nbsp; " : "";
        String scope;
        switch (last == null ? Reading.NOT_CDB : last.container) {
            case Reading.ROOT:
                scope = Labels.getLabel("gui.os.scope.root");
                break;
            case Reading.PDB:
                scope = String.format(Labels.getLabel("gui.os.scope.pdb"), SnapshotPanel.esc(last.containerName));
                break;
            default:
                scope = Labels.getLabel("gui.os.scope.instance");
        }
        scopeLbl.setText("<html>" + platformName + scope + "</html>");
    }

    private void renderCpu(Interval i, SnapshotService.Result timeModel) {
        double cpus = last.os("NUM_CPUS");
        double cores = last.cores();
        double sockets = last.os("NUM_CPU_SOCKETS");
        double load = last.os("LOAD");
        StringBuilder html = new StringBuilder("<html>");
        html.append(String.format(Labels.getLabel("gui.os.cpu.busy"), "<b>" + pct(i.pct("BUSY_TIME")) + "</b>",
                pct(i.pct("USER_TIME")), pct(i.pct("SYS_TIME")), pct(i.pct("IOWAIT_TIME")))).append("<br>");
        html.append(String.format(Labels.getLabel("gui.os.cpu.count"), num(cpus, 0), num(cores, 0),
                sockets > 0 ? num(sockets, 0) : "—")).append("<br>");
        if (last.has("LOAD")) {
            html.append(String.format(Labels.getLabel("gui.os.cpu.load"), "<b>" + num(load, 1) + "</b>",
                    pct(last.loadPerCore()), num(cores, 0))).append("<br>");
        }

        // CPU of this database (container) against the CPU used on the whole server
        double hostCpus = i.delta("BUSY_TIME") / 100.0 / i.seconds;
        if (timeModel != null && timeModel.isOk() && previous.dbCpu >= 0 && last.dbCpu >= 0) {
            double dbCpus = Math.max(0, last.dbCpu - previous.dbCpu) / 1e6 / i.seconds;
            double share = hostCpus <= 0 ? 0 : Math.min(100, dbCpus / hostCpus * 100);
            String key = last.container == Reading.PDB ? "gui.os.cpu.db.pdb"
                    : last.container == Reading.ROOT ? "gui.os.cpu.db.cdb" : "gui.os.cpu.db.instance";
            html.append(String.format(Labels.getLabel(key), "<b>" + num(dbCpus, 1) + "</b>", num(hostCpus, 1),
                    "<b>" + pct(share) + "</b>")).append("<br>");
        } else {
            html.append(Labels.getLabel("gui.os.cpu.db.noperm")).append("<br>");
        }

        // Resource Manager: the database waited for CPU because of a plan, not because the server is full
        if (last.has("RSRC_MGR_CPU_WAIT_TIME")) {
            double rm = i.delta("RSRC_MGR_CPU_WAIT_TIME") / 100.0;
            html.append(String.format(Labels.getLabel("gui.os.cpu.rm"), "<b>" + num(rm, 1) + " s</b>"));
            html.append(" ").append(rm > 0
                    ? "<font color='" + SnapshotPanel.hex(SnapshotPanel.warnColor()) + "'>" + Labels.getLabel("gui.os.cpu.rm.limited") + "</font>"
                    : "<font color='" + SnapshotPanel.hex(SnapshotPanel.secondaryColor()) + "'>" + Labels.getLabel("gui.os.cpu.rm.none") + "</font>");
        }
        cpuSummary.setText(html.append("</html>").toString());
    }

    private void renderMemory(Interval i, SnapshotService.Result oracle) {
        memModel.setRowCount(0);
        double ram = last.os("PHYSICAL_MEMORY_BYTES");
        if (ram <= 0) {
            memSummary.setText(Labels.getLabel("gui.os.mem.none"));
            return;
        }
        memModel.addRow(new Object[]{Labels.getLabel("gui.os.mem.ram"), gb(ram), null});
        addMemRow("gui.os.mem.free", "FREE_MEMORY_BYTES", ram);
        addMemRow("gui.os.mem.cache", "INACTIVE_MEMORY_BYTES", ram);
        if (oracle != null && oracle.isOk() && !oracle.rows.isEmpty()) {
            Object[] row = oracle.rows.get(0);
            if (row[0] instanceof Number) {
                double sga = ((Number) row[0]).doubleValue();
                memModel.addRow(new Object[]{Labels.getLabel("gui.os.mem.sga"), gb(sga), sga / ram * 100});
            }
            if (row[1] instanceof Number) {
                double pga = ((Number) row[1]).doubleValue();
                memModel.addRow(new Object[]{Labels.getLabel("gui.os.mem.pga"), gb(pga), pga / ram * 100});
            }
        }
        if (last.has("SWAP_FREE_BYTES")) {
            memModel.addRow(new Object[]{Labels.getLabel("gui.os.mem.swap"), gb(last.os("SWAP_FREE_BYTES")), null});
        }

        // paging: memory going to / coming from the swap area in the last interval
        if (last.has("VM_IN_BYTES")) {
            double paged = (i.delta("VM_IN_BYTES") + i.delta("VM_OUT_BYTES")) / 1048576.0 / i.seconds;
            String secondary = SnapshotPanel.hex(SnapshotPanel.secondaryColor());
            String paging;
            if (paged >= PAGING_WARN_MBS) {
                paging = String.format(Labels.getLabel("gui.os.mem.paging.on"), "<font color='"
                        + SnapshotPanel.hex(SnapshotPanel.warnColor()) + "'><b>" + num(paged, 1) + " MB/s</b>") + "</font>";
            } else {
                // a little paging now and then is normal: only a steady flow means memory is short
                paging = String.format(Labels.getLabel("gui.os.mem.paging.off"), "<b>" + num(paged, paged > 0 ? 2 : 0)
                        + " MB/s</b>") + " <font color='" + secondary + "'>"
                        + Labels.getLabel(paged > 0 ? "gui.os.mem.paging.low" : "gui.os.mem.paging.normal") + "</font>";
            }
            // limited width: the hint wraps instead of widening the column
            memSummary.setText("<html><div style='width:" + HINT_WIDTH + "px'>" + paging + "<br><font color='" + secondary + "'>"
                    + Labels.getLabel("gui.os.mem.free.hint") + "</font></html>");
        } else {
            memSummary.setText("<html><div style='width:" + HINT_WIDTH + "px'><font color='" + SnapshotPanel.hex(SnapshotPanel.secondaryColor()) + "'>"
                    + Labels.getLabel("gui.os.mem.free.hint") + "</font></html>");
        }
    }

    private void addMemRow(String label, String stat, double ram) {
        if (last.has(stat)) {
            double v = last.os(stat);
            memModel.addRow(new Object[]{Labels.getLabel(label), gb(v), v / ram * 100});
        }
    }

    private void renderPdbs(Interval i, SnapshotService.Result r) {
        boolean show = last.container == Reading.ROOT && r != null && r.isOk() && !previous.pdbCpu.isEmpty();
        pdbSection.setVisible(show);
        pdbModel.setRowCount(0);
        if (!show) {
            return;
        }
        Map<String, Double> cpus = new LinkedHashMap<>();
        double total = 0;
        for (Map.Entry<String, Double> e : last.pdbCpu.entrySet()) {
            Double before = previous.pdbCpu.get(e.getKey());
            if (before == null) {
                continue;
            }
            double c = Math.max(0, e.getValue() - before) / 1e6 / i.seconds;
            cpus.put(e.getKey(), c);
            total += c;
        }
        final double sum = total;
        cpus.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e ->
                pdbModel.addRow(new Object[]{e.getKey(), round(e.getValue(), 2), sum <= 0 ? 0.0 : e.getValue() / sum * 100}));
        // a common user sees the rows of the PDBs only with CONTAINER_DATA=ALL
        boolean rootOnly = cpus.keySet().stream().allMatch("CDB$ROOT"::equalsIgnoreCase);
        pdbSummary.setText(rootOnly
                ? "<html><font color='" + SnapshotPanel.hex(SnapshotPanel.warnColor()) + "'>⚠ "
                + SnapshotPanel.esc(Labels.getLabel("gui.os.pdb.containerdata")) + "</font></html>"
                : String.format(Labels.getLabel("gui.os.pdb.summary"), cpus.size(), num(total, 1)));
    }

    private void renderIo(Interval i, SnapshotService.Result r) {
        ioModel.setRowCount(0);
        if (r == null || !r.isOk()) {
            ioSummary.setText(panel.errorHtml(r));
            return;
        }
        // function -> {read MB, write MB, requests, waits, wait time ms}
        List<Object[]> rows = new ArrayList<>();
        double totalMb = 0;
        for (Map.Entry<String, double[]> e : last.io.entrySet()) {
            double[] before = previous.io.get(e.getKey());
            if (before == null) {
                continue;
            }
            double[] d = new double[5];
            for (int k = 0; k < 5; k++) {
                d[k] = Math.max(0, e.getValue()[k] - before[k]);
            }
            if (d[0] + d[1] + d[2] <= 0) {
                continue;
            }
            totalMb += d[0] + d[1];
            rows.add(new Object[]{e.getKey(), d});
        }
        final double total = totalMb;
        rows.sort((a, b) -> Double.compare(mb((double[]) b[1]), mb((double[]) a[1])));
        for (Object[] row : rows) {
            double[] d = (double[]) row[1];
            // latency: none when the I/O did not wait (e.g. asynchronous direct reads)
            ioModel.addRow(new Object[]{row[0], round(d[0] / i.seconds, 2), round(d[1] / i.seconds, 2),
                    round(d[2] / i.seconds, 0), d[3] < 1 ? null : round(d[4] / d[3], 1),
                    total <= 0 ? 0.0 : (d[0] + d[1]) / total * 100});
        }
        ioSummary.setText("<html>" + String.format(Labels.getLabel("gui.os.io.summary"),
                "<b>" + num(totalMb / i.seconds, 1) + " MB/s</b>", num(i.seconds, 0)) + "</html>");
    }

    private static double mb(double[] d) {
        return d[0] + d[1];
    }

    /* ---------- readings ---------- */

    /** Values of one refresh */
    private static final class Reading {
        static final int NOT_CDB = 0;
        static final int ROOT = 1;
        static final int PDB = 2;

        long time;
        final Map<String, Double> os = new HashMap<>();
        double dbCpu = -1; // microseconds, DB CPU + background cpu time
        final Map<String, double[]> io = new LinkedHashMap<>();
        final Map<String, Double> pdbCpu = new LinkedHashMap<>();
        int container = NOT_CDB;
        String containerName = "";

        static Reading of(Map<String, SnapshotService.Result> r) {
            SnapshotService.Result os = r.get(OracleSnapshotQueries.OS_STAT);
            if (os == null || !os.isOk()) {
                return null;
            }
            Reading reading = new Reading();
            reading.time = System.currentTimeMillis();
            for (Object[] row : os.rows) {
                if (row[1] instanceof Number) {
                    reading.os.put(String.valueOf(row[0]), ((Number) row[1]).doubleValue());
                }
            }
            SnapshotService.Result tm = r.get(OracleSnapshotQueries.OS_TIME_MODEL);
            if (tm != null && tm.isOk() && !tm.rows.isEmpty()) {
                reading.dbCpu = tm.rows.stream().mapToDouble(row -> row[1] instanceof Number ? ((Number) row[1]).doubleValue() : 0).sum();
            }
            SnapshotService.Result io = r.get(OracleSnapshotQueries.OS_IO);
            if (io != null && io.isOk()) {
                for (Object[] row : io.rows) {
                    double[] v = new double[5];
                    for (int k = 0; k < 5; k++) {
                        v[k] = row[k + 1] instanceof Number ? ((Number) row[k + 1]).doubleValue() : 0;
                    }
                    reading.io.put(String.valueOf(row[0]), v);
                }
            }
            SnapshotService.Result con = r.get(OracleSnapshotQueries.OS_CONTAINER);
            if (con != null && con.isOk() && !con.rows.isEmpty()) {
                long id = 0;
                try {
                    id = Long.parseLong(String.valueOf(con.rows.get(0)[0]).trim());
                } catch (NumberFormatException ignore) {
                    // not a CDB
                }
                reading.container = id == 1 ? ROOT : id > 1 ? PDB : NOT_CDB;
                reading.containerName = String.valueOf(con.rows.get(0)[1]);
            }
            SnapshotService.Result pdb = r.get(OracleSnapshotQueries.OS_PDB_CPU);
            if (pdb != null && pdb.isOk()) {
                for (Object[] row : pdb.rows) {
                    if (row[2] instanceof Number) {
                        reading.pdbCpu.merge(String.valueOf(row[0]), ((Number) row[2]).doubleValue(), Double::sum);
                    }
                }
            }
            return reading;
        }

        boolean has(String stat) {
            return os.containsKey(stat);
        }

        double os(String stat) {
            return os.getOrDefault(stat, 0d);
        }

        double cores() {
            double cores = os("NUM_CPU_CORES");
            return cores > 0 ? cores : Math.max(1, os("NUM_CPUS"));
        }

        double loadPerCore() {
            return os("LOAD") / cores() * 100;
        }

        /** Cumulative counters lower than an older reading: the instance restarted */
        boolean before(Reading older) {
            return os("BUSY_TIME") < older.os("BUSY_TIME");
        }
    }

    /** Differences between two readings */
    private static final class Interval {
        final Reading a;
        final Reading b;
        final double seconds;
        final double total; // busy + idle (centiseconds of all CPUs)

        Interval(Reading a, Reading b) {
            this.a = a;
            this.b = b;
            this.seconds = Math.max(0.001, (b.time - a.time) / 1000.0);
            this.total = delta("BUSY_TIME") + delta("IDLE_TIME");
        }

        double delta(String stat) {
            return Math.max(0, b.os(stat) - a.os(stat));
        }

        double pct(String stat) {
            return total <= 0 ? 0 : delta(stat) / total * 100;
        }
    }

    private static final class Point {
        final long time;
        final double[] values; // user, sys, iowait, idle, load per core (%)

        Point(long time, double... values) {
            this.time = time;
            this.values = values;
        }
    }

    /* ---------- chart ---------- */

    /** Lines of user, sys, I/O wait and idle (% of the CPU of the server) and load per core */
    private final class Chart extends JComponent {
        private final String[] names = {Labels.getLabel("gui.os.chart.user"), Labels.getLabel("gui.os.chart.sys"),
                Labels.getLabel("gui.os.chart.iowait"), Labels.getLabel("gui.os.chart.idle"),
                Labels.getLabel("gui.os.chart.load")};

        Chart() {
            setPreferredSize(new Dimension(200, 190));
        }

        private Color[] colors() {
            boolean dark = Themes.isDark();
            return new Color[]{
                    dark ? new Color(90, 200, 110) : new Color(0, 150, 0),      // user
                    dark ? new Color(240, 170, 60) : new Color(200, 120, 0),    // sys
                    dark ? new Color(95, 160, 255) : new Color(40, 110, 220),   // I/O wait
                    dark ? new Color(150, 150, 150) : new Color(140, 140, 140), // idle
                    dark ? new Color(185, 140, 240) : new Color(130, 70, 200)}; // load (dashed)
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                Font small = getFont().deriveFont(getFont().getSize2D() - 1f);
                g2.setFont(small);
                FontMetrics fm = g2.getFontMetrics();
                Color[] colors = colors();
                Color secondary = SnapshotPanel.secondaryColor();

                int legendWidth = 0;
                for (String n : names) {
                    legendWidth = Math.max(legendWidth, fm.stringWidth(n));
                }
                int left = fm.stringWidth("100%") + 10;
                int right = getWidth() - legendWidth - 40;
                int top = 8;
                int bottom = getHeight() - fm.getHeight() - 6;
                int w = right - left;
                int h = bottom - top;
                if (w < 50 || h < 30) {
                    return;
                }

                // the load can pass 100% of the cores: the scale grows in steps of 50%
                double max = 100;
                for (Point p : points) {
                    max = Math.max(max, Math.ceil(p.values[4] / 50) * 50);
                }

                Color grid = Themes.isDark() ? new Color(255, 255, 255, 30) : new Color(0, 0, 0, 25);
                for (int v = 0; v <= max; v += 25) {
                    int y = bottom - (int) Math.round(h * v / max);
                    g2.setColor(grid);
                    g2.drawLine(left, y, right, y);
                    if (v % 50 == 0) {
                        g2.setColor(secondary);
                        String label = v + "%";
                        g2.drawString(label, left - fm.stringWidth(label) - 6, y + fm.getAscent() / 2 - 1);
                    }
                }

                // legend
                for (int s = 0; s < names.length; s++) {
                    int y = top + 6 + s * (fm.getHeight() + 2);
                    g2.setColor(colors[s]);
                    g2.setStroke(stroke(s));
                    g2.drawLine(right + 16, y, right + 30, y);
                    g2.setColor(getForeground());
                    g2.drawString(names[s], right + 36, y + fm.getAscent() / 2 - 1);
                }

                if (points.size() < 2) {
                    g2.setColor(secondary);
                    String msg = Labels.getLabel("gui.os.chart.empty");
                    g2.drawString(msg, left + (w - fm.stringWidth(msg)) / 2, top + h / 2);
                    return;
                }

                long t0 = points.get(0).time;
                long t1 = points.get(points.size() - 1).time;
                double span = Math.max(1, t1 - t0);

                // time scale: a vertical line and the time every ~110 px
                SimpleDateFormat timeFormat = new SimpleDateFormat(t1 - t0 < 10 * 60000 ? "HH:mm:ss" : "HH:mm");
                int ticks = Math.max(1, w / 110);
                int ty = bottom + fm.getAscent() + 3;
                g2.setStroke(new BasicStroke(1f));
                for (int k = 0; k <= ticks; k++) {
                    int x = left + w * k / ticks;
                    String label = timeFormat.format(new Date(t0 + Math.round(span * k / ticks)));
                    g2.setColor(grid);
                    g2.drawLine(x, top, x, bottom);
                    g2.setColor(secondary);
                    int lx = k == 0 ? x : k == ticks ? x - fm.stringWidth(label) : x - fm.stringWidth(label) / 2;
                    g2.drawString(label, lx, ty);
                }

                for (int s = 0; s < names.length; s++) {
                    g2.setColor(colors[s]);
                    g2.setStroke(stroke(s));
                    for (int k = 1; k < points.size(); k++) {
                        Point p0 = points.get(k - 1);
                        Point p1 = points.get(k);
                        int x0 = left + (int) Math.round(w * (p0.time - t0) / span);
                        int x1 = left + (int) Math.round(w * (p1.time - t0) / span);
                        int y0 = bottom - (int) Math.round(h * Math.min(max, p0.values[s]) / max);
                        int y1 = bottom - (int) Math.round(h * Math.min(max, p1.values[s]) / max);
                        g2.drawLine(x0, y0, x1, y1);
                    }
                }

            } finally {
                g2.dispose();
            }
        }

        private Stroke stroke(int series) {
            return series == 4
                    ? new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 4f}, 0f)
                    : new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }
    }

    /* ---------- formatting ---------- */

    private static String pct(double v) {
        return num(v, 0) + "%";
    }

    private static Double gb(double bytes) {
        return round(bytes / GB, bytes / GB < 1 ? 2 : 1);
    }

    private static Double round(double v, int decimals) {
        double f = Math.pow(10, decimals);
        return Math.round(v * f) / f;
    }

    private static String num(double v, int decimals) {
        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMinimumFractionDigits(decimals);
        nf.setMaximumFractionDigits(decimals);
        return nf.format(v);
    }
}
