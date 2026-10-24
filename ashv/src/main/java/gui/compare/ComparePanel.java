package gui.compare;

import com.github.lgooddatepicker.components.DatePickerSettings;
import com.github.lgooddatepicker.components.DateTimePicker;
import com.github.lgooddatepicker.components.TimePickerSettings;
import config.Labels;
import config.profile.SqlColProfile;
import core.compare.PeriodComparison;
import core.filter.AshFilter;
import core.filter.FilteredAshLoader;
import core.manager.ColorManager;
import gui.filter.FilterPopup;
import gui.util.CsvExport;
import gui.util.SqlTextTooltip;
import gui.util.Themes;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.HierarchyEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jdesktop.swingx.JXTable;
import org.jdesktop.swingx.decorator.ColorHighlighter;
import org.jdesktop.swingx.decorator.HighlightPredicate;
import org.jfree.chart.util.GanttParam;
import org.jfree.chart.util.IDetailPanel;
import profile.IProfile;
import store.StoreManager;

/**
 * "Comparar" tab: period A (reference) and period B (problem) of the local history, per wait class and
 * per value of a column, the biggest increase first
 */
@Slf4j
public class ComparePanel extends JPanel implements IDetailPanel {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 24 * HOUR;

    // Columns of "Comparar por" (shown when collected)
    private static final List<String> DIMENSIONS = Arrays.asList(
            "SQL_ID", "EVENT", "MODULE", "ACTION", "PROGRAM", "USERNAME", "MACHINE", PeriodComparison.SESSION,
            "SERVICE_NAME", "CLIENT_IDENTIFIER", "COMMAND", "CURRENT_OBJ#", "PLSQL_ENTRY_OBJECT_ID",
            "SESSION_TYPE", "OSUSER", "PREV_SQL_ID");

    private final StoreManager storeManager;
    private final AshFilter ashFilter;
    private final Supplier<long[]> defaultRange;

    private final DateTimePicker startA = picker();
    private final DateTimePicker endA = picker();
    private final DateTimePicker startB = picker();
    private final DateTimePicker endB = picker();
    private final JButton useSelection = new JButton(Labels.getLabel("gui.compare.selection"));
    private final JButton compare = new JButton(Labels.getLabel("gui.compare.button"));
    private final JLabel summary = new JLabel(" ");
    private final JComboBox<String> dimensionBox = new JComboBox<>();
    private final JComboBox<String> measureBox = new JComboBox<>(new String[]{
            Labels.getLabel("gui.compare.measure.average"), Labels.getLabel("gui.compare.measure.total")});
    private final JButton export = new JButton(Labels.getLabel("gui.export.button"));
    private final JPanel waitClassContent = new JPanel(new BorderLayout());
    private final JPanel itemsContent = new JPanel(new BorderLayout());
    private final PeriodChart chartA;
    private final PeriodChart chartB;
    /** Reload a chart a moment after its pickers change (typing, both pickers set) */
    private final Timer chartTimerA = new Timer(500, e -> loadChart(true));
    private final Timer chartTimerB = new Timer(500, e -> loadChart(false));
    private boolean chartsPending;

    private final AtomicInteger generation = new AtomicInteger();
    private volatile long[] selectedRange;
    private boolean initialized;
    private boolean pending;
    private PeriodComparison last;
    private IProfile lastProfile;
    private ItemsModel lastItems;
    private boolean updatingBox;
    /** Pickers changed by the panel, not by the user */
    private boolean adjusting;

    /**
     * @param defaultRange period B before a choice (A: the same time the day before)
     */
    public ComparePanel(StoreManager storeManager, AshFilter ashFilter, FilteredAshLoader filteredAshLoader,
                        ColorManager colorManager, Supplier<long[]> defaultRange) {
        super(new BorderLayout());
        this.storeManager = storeManager;
        this.ashFilter = ashFilter;
        this.defaultRange = defaultRange;
        this.chartA = new PeriodChart(filteredAshLoader, colorManager, r -> set(startA, endA, r), this::sameScale);
        this.chartB = new PeriodChart(filteredAshLoader, colorManager, r -> set(startB, endB, r), this::sameScale);
        chartTimerA.setRepeats(false);
        chartTimerB.setRepeats(false);

        JButton yesterday = new JButton(Labels.getLabel("gui.compare.yesterday"));
        yesterday.addActionListener(e -> shiftA(DAY));
        JButton lastWeek = new JButton(Labels.getLabel("gui.compare.lastweek"));
        lastWeek.addActionListener(e -> shiftA(7 * DAY));
        JButton previous = new JButton(Labels.getLabel("gui.compare.previous"));
        previous.setToolTipText(Labels.getLabel("gui.compare.previous.tooltip"));
        previous.addActionListener(e -> {
            long[] b = range(startB, endB);
            if (b != null) shiftA(b[1] - b[0]);
        });
        useSelection.setToolTipText(Labels.getLabel("gui.compare.selection.tooltip"));
        useSelection.setEnabled(false);
        useSelection.addActionListener(e -> {
            if (selectedRange != null) {
                set(startB, endB, selectedRange);
                shiftA(DAY);
            }
        });
        compare.addActionListener(e -> load());
        keepDuration(startA, endA);
        keepDuration(startB, endB);
        startA.addDateTimeChangeListener(e -> chartTimerA.restart());
        endA.addDateTimeChangeListener(e -> chartTimerA.restart());
        startB.addDateTimeChangeListener(e -> chartTimerB.restart());
        endB.addDateTimeChangeListener(e -> chartTimerB.restart());

        // period A, its chart, period B, its chart
        JPanel top = new JPanel(new MigLayout("ins 6, gap 6 3, fill", "[right][][][][][][][grow]", "[][grow][][grow]"));
        top.add(new JLabel(Labels.getLabel("gui.compare.period.a")));
        top.add(startA);
        top.add(new JLabel(Labels.getLabel("gui.compare.until")));
        top.add(endA);
        top.add(yesterday, "gapleft 12");
        top.add(lastWeek);
        top.add(previous, "wrap");
        top.add(chartA, "span, grow, wrap");
        top.add(new JLabel(Labels.getLabel("gui.compare.period.b")));
        top.add(startB);
        top.add(new JLabel(Labels.getLabel("gui.compare.until")));
        top.add(endB);
        top.add(useSelection, "gapleft 12, span 2, wrap");
        top.add(chartB, "span, grow, wrap");
        // main action: centered under chart B, larger, highlighted
        compare.setFont(compare.getFont().deriveFont(Font.BOLD, compare.getFont().getSize2D() + 2f));
        compare.setBackground(new Color(30, 110, 200));
        compare.setForeground(Color.WHITE);
        top.add(compare, "span, center, w 240!, h 36!, gaptop 4");

        JPanel results = new JPanel(new MigLayout("ins 4 6 2 6, gap 6 3", "[right][]"));
        measureBox.setToolTipText(Labels.getLabel("gui.compare.measure.tooltip"));
        measureBox.addActionListener(e -> {
            if (last != null) show(last, lastProfile);
        });
        results.add(new JLabel(Labels.getLabel("gui.compare.measure")));
        results.add(measureBox, "wrap");
        results.add(summary, "span, growx, gapleft 4");

        dimensionBox.setToolTipText(Labels.getLabel("gui.compare.by.tooltip"));
        dimensionBox.addActionListener(e -> {
            if (!updatingBox && last != null) showItems();
        });
        export.setToolTipText(Labels.getLabel("gui.export.tooltip"));
        export.setEnabled(false);
        export.addActionListener(e -> exportItems());
        JPanel itemsBar = new JPanel(new MigLayout("ins 4 6 2 6", "[][][]"));
        itemsBar.add(new JLabel(Labels.getLabel("gui.compare.by")));
        itemsBar.add(dimensionBox);
        itemsBar.add(export, "gapleft 12");
        JPanel itemsPanel = new JPanel(new BorderLayout());
        itemsPanel.add(itemsBar, BorderLayout.NORTH);
        itemsPanel.add(itemsContent, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, waitClassContent, itemsPanel);
        split.setDividerLocation(170);
        split.setResizeWeight(0.2);
        split.setOneTouchExpandable(true);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(results, BorderLayout.NORTH);
        bottom.add(split, BorderLayout.CENTER);

        // charts / results: the divider gives more room to one or the other
        JSplitPane outer = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        outer.setDividerLocation(440);
        outer.setResizeWeight(0.5);
        outer.setOneTouchExpandable(true);
        add(outer, BorderLayout.CENTER);
        showMessage(waitClassContent, Labels.getLabel("gui.compare.help"));

        ashFilter.addListener(() -> SwingUtilities.invokeLater(() -> {
            if (!initialized) return;
            if (isShowing()) {
                loadCharts();
            } else {
                chartsPending = true;
            }
            if (last != null) requestLoad();
        }));
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(this::onShow);
            }
        });
    }

    /** Selection in the Top Activity chart: offered as period B */
    @Override
    public void LoadDataToDetail(GanttParam ganttParam) {
        selectedRange = new long[]{(long) ganttParam.getBeginTime(), (long) ganttParam.getEndTime()};
        SwingUtilities.invokeLater(() -> useSelection.setEnabled(true));
    }

    private void onShow() {
        if (!initialized) {
            initialized = true;
            long[] b = defaultRange.get();
            if (b == null) {
                long now = System.currentTimeMillis();
                b = new long[]{now - HOUR, now};
            }
            set(startB, endB, b);
            shiftA(DAY);
        }
        if (chartsPending) {
            loadCharts();
        }
        if (pending) {
            load();
        }
    }

    private void requestLoad() {
        if (isShowing()) {
            load();
        } else {
            pending = true;
        }
    }

    private void loadCharts() {
        loadChart(true);
        loadChart(false);
    }

    private void loadChart(boolean isA) {
        if (!isShowing()) {
            chartsPending = true;
            return;
        }
        chartsPending = false;
        PeriodChart chart = isA ? chartA : chartB;
        long[] range = isA ? range(startA, endA) : range(startB, endB);
        if (range == null) {
            chart.clear(Labels.getLabel("gui.compare.invalid"));
            sameScale();
            return;
        }
        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        chart.load(range, ashFilter.matcher(cols == null ? Collections.emptyList() : cols),
                storeManager.getConfigurationManager().getIProfile());
    }

    /** Filter bar of the tab (the same filter as Top Activity and History) */
    public void setFilterBar(JComponent filterBar) {
        add(filterBar, BorderLayout.NORTH);
        revalidate();
    }

    /** Values offered by the filter dialog: both periods when close, otherwise period B */
    public long[] getFilterRange() {
        long[] a = range(startA, endA);
        long[] b = range(startB, endB);
        if (b == null) {
            return a != null ? a : defaultRange.get();
        }
        if (a == null) {
            return b;
        }
        long[] both = {Math.min(a[0], b[0]), Math.max(a[1], b[1])};
        return both[1] - both[0] <= 26 * HOUR ? both : b;
    }

    /** Both charts up to the highest value of the two */
    private void sameScale() {
        double upper = Math.max(chartA.getMax(), chartB.getMax());
        chartA.setUpperBound(upper);
        chartB.setUpperBound(upper);
    }

    /** Changing the beginning moves the end too (same duration); the end alone changes the duration */
    private void keepDuration(DateTimePicker begin, DateTimePicker end) {
        begin.addDateTimeChangeListener(e -> {
            LocalDateTime before = e.getOldDateTimePermissive();
            LocalDateTime after = e.getNewDateTimePermissive();
            LocalDateTime endTime = end.getDateTimePermissive();
            if (adjusting || before == null || after == null || endTime == null) {
                return;
            }
            adjusting = true;
            try {
                end.setDateTimePermissive(endTime.plus(Duration.between(before, after)));
            } finally {
                adjusting = false;
            }
        });
    }

    /** Period A = period B moved back */
    private void shiftA(long back) {
        long[] b = range(startB, endB);
        if (b != null) {
            set(startA, endA, new long[]{b[0] - back, b[1] - back});
        }
    }

    private void load() {
        pending = false;
        long[] a = range(startA, endA);
        long[] b = range(startB, endB);
        if (a == null || b == null) {
            summary.setText(Labels.getLabel("gui.compare.invalid"));
            return;
        }
        int gen = generation.incrementAndGet();
        compare.setEnabled(false);
        export.setEnabled(false);
        summary.setText(Labels.getLabel("gui.compare.loading"));

        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        AshFilter.Matcher matcher = ashFilter.matcher(cols == null ? Collections.emptyList() : cols);
        IProfile iProfile = storeManager.getConfigurationManager().getIProfile();

        Thread t = new Thread(() -> {
            PeriodComparison result;
            try {
                result = PeriodComparison.load(storeManager, iProfile, matcher, a, b, DIMENSIONS,
                        () -> generation.get() != gen);
            } catch (Exception e) {
                log.error("Compare periods", e);
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) {
                        compare.setEnabled(true);
                        summary.setText(Labels.getLabel("gui.compare.error") + " " + e);
                    }
                });
                return;
            }
            if (result != null) {
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) show(result, iProfile);
                });
            }
        }, "compare-periods");
        t.setDaemon(true);
        t.start();
    }

    private void show(PeriodComparison result, IProfile iProfile) {
        compare.setEnabled(true);
        last = result;
        lastProfile = iProfile;
        PeriodComparison.Measure measure = measure();
        summary.setText(summaryText(result, measure));

        ItemsModel waitClasses = new ItemsModel(Labels.getLabel("gui.compare.col.waitclass"),
                result.waitClasses(measure), result, measure);
        JXTable waitClassTable = table(waitClasses);
        FilterPopup.install(waitClassTable, ashFilter, (row, col) ->
                conditions(waitClassTable, row, col, iProfile.getWaitClassColName(), iProfile));
        setContent(waitClassContent, waitClassTable);

        String selected = (String) dimensionBox.getSelectedItem();
        updatingBox = true;
        dimensionBox.removeAllItems();
        for (String d : result.getDimensions()) {
            dimensionBox.addItem(d);
        }
        dimensionBox.setSelectedItem(selected != null && result.getDimensions().contains(selected)
                ? selected : result.getDimensions().isEmpty() ? null : result.getDimensions().get(0));
        updatingBox = false;
        showItems();
    }

    private void showItems() {
        String dimension = (String) dimensionBox.getSelectedItem();
        if (last == null || dimension == null) {
            return;
        }
        PeriodComparison.Measure measure = measure();
        ItemsModel model = new ItemsModel(label(dimension), last.items(dimension, measure), last, measure);
        lastItems = model;
        JXTable table = table(model);
        IProfile iProfile = lastProfile;
        FilterPopup.install(table, ashFilter, (row, col) -> conditions(table, row, col, dimension, iProfile));
        if (dimension.endsWith("SQL_ID")) {
            SqlTextTooltip.install(table, (row, col) -> table.convertColumnIndexToModel(col) == ItemsModel.COL_VALUE
                    ? String.valueOf(table.getValueAt(row, col)) : null);
        }
        setContent(itemsContent, table);
        export.setEnabled(model.getRowCount() > 0);
    }

    private PeriodComparison.Measure measure() {
        return measureBox.getSelectedIndex() == 1 ? PeriodComparison.Measure.TOTAL : PeriodComparison.Measure.AVERAGE;
    }

    /** Right click: filter by the value of the row (or by its main event) */
    private List<List<Map.Entry<String, String>>> conditions(JTable table, int row, int col, String colName,
                                                             IProfile iProfile) {
        int modelCol = table.convertColumnIndexToModel(col);
        String value = String.valueOf(table.getModel().getValueAt(table.convertRowIndexToModel(row),
                modelCol == ItemsModel.COL_EVENT ? ItemsModel.COL_EVENT : ItemsModel.COL_VALUE));
        if (modelCol == ItemsModel.COL_EVENT) {
            return value.isEmpty() ? Collections.emptyList()
                    : Collections.singletonList(Collections.singletonList(FilterPopup.condition(iProfile.getEventColName(), value)));
        }
        if (PeriodComparison.SESSION.equals(colName)) {
            String[] sidSerial = value.split(",", -1);
            List<Map.Entry<String, String>> both = new ArrayList<>();
            both.add(FilterPopup.condition(iProfile.getSessColName(), sidSerial[0]));
            if (sidSerial.length > 1 && !sidSerial[1].isEmpty()) {
                both.add(FilterPopup.condition(iProfile.getSerialColName(), sidSerial[1]));
            }
            return Collections.singletonList(both);
        }
        return Collections.singletonList(Collections.singletonList(FilterPopup.condition(colName, value)));
    }

    private String summaryText(PeriodComparison result, PeriodComparison.Measure measure) {
        PeriodComparison.Period a = result.getA();
        PeriodComparison.Period b = result.getB();
        boolean total = measure == PeriodComparison.Measure.TOTAL;
        String unit = total ? Labels.getLabel("gui.compare.dbtime") + " " : "";
        String after = total ? "" : " " + Labels.getLabel("gui.compare.aas");
        StringBuilder sb = new StringBuilder("<html>");
        sb.append("<b>A</b> (").append(duration(a)).append("): ").append(unit).append(format(a.total(measure), measure))
                .append(after).append(" (CPU ").append(format(a.cpu(measure), measure)).append(")");
        sb.append(" &nbsp;&nbsp; <b>B</b> (").append(duration(b)).append("): ").append(unit).append(format(b.total(measure), measure))
                .append(after).append(" (CPU ").append(format(b.cpu(measure), measure)).append(")");
        double delta = b.total(measure) - a.total(measure);
        sb.append(" &nbsp;&nbsp; <b>").append(Labels.getLabel("gui.compare.col.delta")).append(":</b> ")
                .append(signed(delta, measure));
        if (a.total(measure) > 0) {
            sb.append(" (").append(percent(delta * 100 / a.total(measure))).append(")");
        }
        if (ashFilter.isActive()) {
            sb.append(" &nbsp;&nbsp; <i>").append(Labels.getLabel("gui.compare.filtered")).append("</i>");
        }
        StringBuilder warnings = new StringBuilder();
        long durationA = a.getEnd() - a.getBegin();
        long durationB = b.getEnd() - b.getBegin();
        if (!total && Math.max(durationA, durationB) > 1.5 * Math.min(durationA, durationB)) {
            warnings.append(" &nbsp;&nbsp; <font color='#e0a030'><b>⚠ ").append(Labels.getLabel("gui.compare.durations"))
                    .append("</b></font>");
        }
        appendCoverage(warnings, "A", a);
        appendCoverage(warnings, "B", b);
        if (warnings.length() > 0) {
            sb.append("<br>").append(warnings.substring(" &nbsp;&nbsp; ".length()));
        }
        return sb.append("</html>").toString();
    }

    private static void appendCoverage(StringBuilder sb, String name, PeriodComparison.Period p) {
        if (p.getSamples() == 0) {
            sb.append(" &nbsp;&nbsp; <font color='#e0a030'><b>⚠ ").append(name).append(": ")
                    .append(Labels.getLabel("gui.compare.nodata")).append("</b></font>");
        } else if (p.getCoverage() < 0.9) {
            sb.append(" &nbsp;&nbsp; <font color='#e0a030'><b>⚠ ").append(name).append(": ")
                    .append(Labels.getLabel("gui.compare.coverage")).append(" ")
                    .append(Math.round(p.getCoverage() * 100)).append("%</b></font>");
        }
    }

    /** 45min, 1h, 1h30, 25h */
    private static String duration(PeriodComparison.Period p) {
        long minutes = Math.round((p.getEnd() - p.getBegin()) / 60000.0);
        if (minutes < 60) {
            return minutes + "min";
        }
        return minutes / 60 + "h" + (minutes % 60 == 0 ? "" : String.format("%02d", minutes % 60));
    }

    private void exportItems() {
        if (lastItems == null || last == null) {
            return;
        }
        List<String> header = new ArrayList<>();
        for (int col = 0; col < lastItems.getColumnCount(); col++) {
            if (col != ItemsModel.COL_BAR) header.add(lastItems.getColumnName(col));
        }
        List<Object[]> rows = new ArrayList<>();
        for (int row = 0; row < lastItems.getRowCount(); row++) {
            List<Object> values = new ArrayList<>();
            for (int col = 0; col < lastItems.getColumnCount(); col++) {
                if (col == ItemsModel.COL_BAR) continue;
                Object v = lastItems.getValueAt(row, col);
                values.add(v instanceof Double && ((Double) v).isNaN() ? null : v);
            }
            rows.add(values.toArray());
        }
        PeriodComparison.Period b = last.getB();
        CsvExport.save(this, CsvExport.fileName(
                storeManager.getConfigurationManager().getCurrentConfiguration().getConfigName(),
                "Comparacao_" + dimensionBox.getSelectedItem(), b.getBegin(), b.getEnd()), header, rows);
    }

    private static JXTable table(ItemsModel model) {
        JXTable table = new JXTable(model);
        table.setColumnControlVisible(true);
        // columns share the whole width of the window
        table.setHorizontalScrollEnabled(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setDefaultRenderer(Double.class, new NumberRenderer());
        // increase blue, decrease green (difference and change columns); not red: red reads as a negative number
        boolean dark = Themes.isDark();
        table.addHighlighter(new ColorHighlighter(deltaPredicate(true), null, increaseColor(dark), null, null));
        table.addHighlighter(new ColorHighlighter(deltaPredicate(false), null, decreaseColor(dark), null, null));
        table.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                setText(value == null || String.valueOf(value).isEmpty() ? Labels.getLabel("gui.filter.empty") : String.valueOf(value));
            }
        });
        table.getColumnExt(ItemsModel.COL_BAR).setCellRenderer(new DeltaBarRenderer());
        table.packAll();
        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(
                    Math.min(table.getColumnModel().getColumn(i).getPreferredWidth(), 320));
        }
        table.getColumnExt(ItemsModel.COL_BAR).setPreferredWidth(220);
        table.getColumnExt(ItemsModel.COL_BAR).setMinWidth(80);
        return table;
    }

    static Color increaseColor(boolean dark) {
        return dark ? new Color(95, 160, 255) : new Color(25, 100, 210);
    }

    static Color decreaseColor(boolean dark) {
        return dark ? new Color(95, 195, 110) : new Color(30, 140, 60);
    }

    private static HighlightPredicate deltaPredicate(boolean increase) {
        return (renderer, adapter) -> {
            int col = adapter.convertColumnIndexToModel(adapter.column);
            if (col != ItemsModel.COL_DELTA && col != ItemsModel.COL_CHANGE) {
                return false;
            }
            // same rule as the bar and "Situação"
            ItemsModel model = (ItemsModel) ((JTable) adapter.getComponent()).getModel();
            PeriodComparison.State state = model.state(adapter.convertRowIndexToModel(adapter.row));
            return increase ? state == PeriodComparison.State.UP || state == PeriodComparison.State.NEW
                    : state == PeriodComparison.State.DOWN || state == PeriodComparison.State.GONE;
        };
    }

    private static void setContent(JPanel panel, JTable table) {
        panel.removeAll();
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        panel.revalidate();
        panel.repaint();
    }

    private static void showMessage(JPanel panel, String text) {
        panel.removeAll();
        panel.add(new JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER);
        panel.revalidate();
        panel.repaint();
    }

    private static String label(String dimension) {
        return PeriodComparison.SESSION.equals(dimension) ? Labels.getLabel("gui.compare.col.session") : dimension;
    }

    private static DateTimePicker picker() {
        DatePickerSettings date = new DatePickerSettings();
        date.setAllowEmptyDates(false);
        TimePickerSettings time = new TimePickerSettings();
        time.setAllowEmptyTimes(false);
        time.setFormatForDisplayTime("HH:mm");
        time.setFormatForMenuTimes("HH:mm");
        return new DateTimePicker(date, time);
    }

    private void set(DateTimePicker begin, DateTimePicker end, long[] range) {
        adjusting = true;
        try {
            begin.setDateTimePermissive(LocalDateTime.ofInstant(Instant.ofEpochMilli(range[0]), ZoneId.systemDefault()));
            end.setDateTimePermissive(LocalDateTime.ofInstant(Instant.ofEpochMilli(range[1]), ZoneId.systemDefault()));
        } finally {
            adjusting = false;
        }
    }

    /** null when empty or end before begin */
    private static long[] range(DateTimePicker begin, DateTimePicker end) {
        LocalDateTime b = begin.getDateTimePermissive();
        LocalDateTime e = end.getDateTimePermissive();
        if (b == null || e == null) {
            return null;
        }
        long from = b.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long to = e.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return to > from ? new long[]{from, to} : null;
    }

    private static final DecimalFormatSymbols PT_BR = DecimalFormatSymbols.getInstance(new Locale("pt", "BR"));

    static String number(double value) {
        return new DecimalFormat("0.00", PT_BR).format(value);
    }

    static String signed(double value) {
        return new DecimalFormat("+0.00;-0.00", PT_BR).format(value);
    }

    /** Average: 2 decimals; total: time (45s, 18min 20s, 2h 05min) */
    static String format(double value, PeriodComparison.Measure measure) {
        return measure == PeriodComparison.Measure.TOTAL ? time(value) : number(value);
    }

    static String signed(double value, PeriodComparison.Measure measure) {
        if (measure == PeriodComparison.Measure.TOTAL) {
            return (value < 0 ? "-" : "+") + time(Math.abs(value));
        }
        return signed(value);
    }

    static String time(double seconds) {
        long s = Math.round(seconds);
        if (seconds > 0 && seconds < 10) {
            return new DecimalFormat("0.#", PT_BR).format(seconds) + "s";
        }
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return s / 60 + "min" + (s % 60 == 0 ? "" : String.format(" %02ds", s % 60));
        }
        return s / 3600 + "h" + String.format(" %02dmin", (s % 3600) / 60);
    }

    static String percent(double value) {
        return new DecimalFormat("+0;-0", PT_BR).format(value) + "%";
    }

    /** Values in the measure of the model (2 decimals or time), the difference with sign, the change in % */
    private static final class NumberRenderer extends DefaultTableCellRenderer {
        NumberRenderer() {
            setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            int modelCol = table.convertColumnIndexToModel(column);
            PeriodComparison.Measure measure = ((ItemsModel) table.getModel()).measure;
            double v = value instanceof Double ? (Double) value : 0;
            if (modelCol == ItemsModel.COL_CHANGE) {
                setText(Double.isNaN(v) ? "—" : percent(v));
            } else if (modelCol == ItemsModel.COL_DELTA) {
                setText(signed(v, measure));
            } else if (modelCol == ItemsModel.COL_SHARE || modelCol == ItemsModel.COL_SHARE_A) {
                setText(new DecimalFormat("0", PT_BR).format(v) + "%");
            } else {
                setText(v == 0 ? "—" : format(v, measure));
            }
            return this;
        }
    }

    /**
     * Difference B - A from the center: to the right blue (worse), to the left green (better), gray when stable;
     * the biggest difference of the table fills half the cell
     */
    private static final class DeltaBarRenderer extends javax.swing.JComponent implements TableCellRenderer {
        private JTable table;
        private double delta;
        private double max;
        private PeriodComparison.State state;
        private boolean selected;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            ItemsModel model = (ItemsModel) table.getModel();
            this.table = table;
            this.delta = value instanceof Double ? (Double) value : 0;
            this.max = model.maxDelta;
            this.state = model.state(table.convertRowIndexToModel(row));
            this.selected = isSelected;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(selected ? table.getSelectionBackground() : table.getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            boolean dark = Themes.isDark();
            int x0 = 4;
            int width = getWidth() - 8;
            int mid = x0 + width / 2;
            int h = Math.max(6, getHeight() - 8);
            int y = (getHeight() - h) / 2;
            g.setColor(dark ? new Color(255, 255, 255, 25) : new Color(0, 0, 0, 18));
            g.fillRect(x0, y, width, h);
            if (max > 0 && delta != 0) {
                int w = (int) Math.max(1, Math.round((width / 2.0) * Math.abs(delta) / max));
                g.setColor(color(dark));
                if (delta > 0) {
                    g.fillRect(mid, y, Math.min(w, x0 + width - mid), h);
                } else {
                    g.fillRect(Math.max(x0, mid - w), y, Math.min(w, mid - x0), h);
                }
            }
            g.setColor(dark ? new Color(200, 200, 200) : new Color(90, 90, 90));
            g.drawLine(mid, y - 2, mid, y + h + 1);
        }

        private Color color(boolean dark) {
            switch (state) {
                case UP:
                case NEW:
                    return increaseColor(dark);
                case DOWN:
                case GONE:
                    return decreaseColor(dark);
                default:
                    return new Color(150, 150, 150);
            }
        }
    }

    static final class ItemsModel extends AbstractTableModel {
        static final int COL_VALUE = 0;
        static final int COL_A = 1;
        static final int COL_B = 2;
        static final int COL_BAR = 3;
        static final int COL_DELTA = 4;
        static final int COL_CHANGE = 5;
        static final int COL_SHARE_A = 6;
        static final int COL_SHARE = 7;
        static final int COL_STATE = 8;
        static final int COL_EVENT = 9;

        private final String valueName;
        private final List<PeriodComparison.Item> items;
        private final double totalA;
        private final double totalB;
        final PeriodComparison.Measure measure;
        /** Biggest difference of the table: the full half of the bar */
        final double maxDelta;

        ItemsModel(String valueName, List<PeriodComparison.Item> items, PeriodComparison result,
                   PeriodComparison.Measure measure) {
            this.valueName = valueName;
            this.items = items;
            this.totalA = result.getA().total(measure);
            this.totalB = result.getB().total(measure);
            this.measure = measure;
            this.maxDelta = items.stream().mapToDouble(i -> Math.abs(i.getDelta())).max().orElse(0);
        }

        PeriodComparison.State state(int row) {
            return items.get(row).getState();
        }

        @Override public int getRowCount() { return items.size(); }
        @Override public int getColumnCount() { return 10; }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case COL_VALUE: return valueName;
                case COL_A: return Labels.getLabel("gui.compare.col.a") + unit();
                case COL_B: return Labels.getLabel("gui.compare.col.b") + unit();
                case COL_BAR: return Labels.getLabel("gui.compare.col.bar");
                case COL_DELTA: return Labels.getLabel("gui.compare.col.delta") + unit();
                case COL_CHANGE: return Labels.getLabel("gui.compare.col.change");
                case COL_SHARE_A: return Labels.getLabel("gui.compare.col.share.a");
                case COL_SHARE: return Labels.getLabel("gui.compare.col.share");
                case COL_STATE: return Labels.getLabel("gui.compare.col.state");
                default: return Labels.getLabel("gui.compare.col.event");
            }
        }

        /** Seconds in the CSV (and in the header) for the total */
        private String unit() {
            return measure == PeriodComparison.Measure.TOTAL ? " (s)" : "";
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column >= COL_A && column <= COL_SHARE ? Double.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            PeriodComparison.Item item = items.get(row);
            switch (column) {
                case COL_VALUE: return item.getValue();
                case COL_A: return item.getA();
                case COL_B: return item.getB();
                case COL_BAR:
                case COL_DELTA: return item.getDelta();
                case COL_CHANGE: return item.getChange();
                case COL_SHARE_A: return totalA == 0 ? 0.0 : item.getA() * 100 / totalA;
                case COL_SHARE: return totalB == 0 ? 0.0 : item.getB() * 100 / totalB;
                case COL_STATE: return Labels.getLabel("gui.compare.state." + item.getState().name().toLowerCase());
                default: return item.getTopEvent();
            }
        }
    }
}
