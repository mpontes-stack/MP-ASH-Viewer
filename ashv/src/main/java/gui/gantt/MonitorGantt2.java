package gui.gantt;

import com.egantt.model.drawing.ContextResources;
import com.egantt.model.drawing.DrawingState;
import com.egantt.swing.component.ComponentResources;
import com.egantt.swing.component.context.BasicComponentContext;
import com.egantt.swing.component.tooltip.ToolTipState;
import com.egantt.swing.table.list.BasicJTableList;
import core.filter.AshFilter;
import core.filter.FilteredAshLoader;
import core.manager.ColorManager;
import core.manager.ConstantManager;
import core.processing.GetFromRemoteAndStore;
import ext.egantt.drawing.painter.context.BasicPainterContext;
import ext.egantt.swing.GanttTable;
import gui.BasicFrame;
import gui.detail.SessionDetail;
import gui.detail.SqlDetail;
import gui.filter.FilterPopup;
import config.Labels;
import core.compare.PeriodComparison;
import gui.util.CsvExport;
import gui.util.SqlTextTooltip;
import gui.util.ProgressBarUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jdesktop.swingx.JXTable;
import org.jfree.chart.util.GanttParam;
import org.jfree.chart.util.IDetailPanel;
import profile.IProfile;
import store.StoreManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
public class MonitorGantt2 extends JPanel implements IDetailPanel {
    private BasicFrame jFrame;
    private StoreManager storeManager;
    private GetFromRemoteAndStore getFromRemoteAndStore;
    private ColorManager colorManager;

    private JPanel main;

    private JSplitPane splitPaneSqlSession;

    private GanttDataById2 ganttDataByIdSqlId;
    private GanttDataById2 ganttDataByIdSessionId;

    private String[][] columnNamesSqls = {{"Activity %", "SQL ID", "SQL type"}};

    /** Executions are counted from the raw samples: only for selections up to this length */
    private static final long EXEC_STATS_MAX_RANGE = 6 * 3600_000L;
    /** Executions per SQL_ID of the selection; null when SQL_EXEC_ID is not collected (columns hidden) */
    private volatile Map<String, PeriodComparison.ExecutionStats> execStats;
    private volatile boolean execStatsTooLong;
    private String[][] columnNamesSessions = {{"Activity %", "Session ID", "Session Serial#", "Username", "Program"}};

    @Getter @Setter private String waitClassG = "";

    @Getter @Setter private IProfile iProfile;

    @Getter @Setter private GanttParam ganttParam;

    @Getter @Setter private boolean isHistory;

    @Getter private AshFilter ashFilter;
    private FilteredAshLoader filteredAshLoader;
    private GanttParam lastGanttParamIn;

    public MonitorGantt2(BasicFrame jFrame, StoreManager storeManager,
                         GetFromRemoteAndStore getFromRemoteAndStore, ColorManager colorManager) {
        this.jFrame = jFrame;
        this.storeManager = storeManager;
        this.getFromRemoteAndStore = getFromRemoteAndStore;
        this.colorManager = colorManager;
        this.init();
    }

    /**
     * Enable filters: data from raw data when a filter is active, reload on filter change
     */
    public void setAshFilter(AshFilter ashFilter) {
        setAshFilter(ashFilter, true);
    }

    /**
     * @param reloadOnChange false for short-lived panels reloaded by their owner
     */
    public void setAshFilter(AshFilter ashFilter, boolean reloadOnChange) {
        this.ashFilter = ashFilter;
        this.filteredAshLoader = new FilteredAshLoader(storeManager, ashFilter);
        if (reloadOnChange) this.ashFilter.addListener(() -> {
            if (lastGanttParamIn != null) {
                SwingUtilities.invokeLater(() -> LoadDataToDetail(lastGanttParamIn));
            }
        });
    }

    @Override
    public void LoadDataToDetail(GanttParam ganttParamIn) {
        this.lastGanttParamIn = ganttParamIn;
        ProgressBarUtil.loadProgressBar(this, this.main, "Loading, please wait...");
        Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    setTimeRange();
                    initGui();
                    loadDataToJPanelsPrivate0(ganttParamIn);
                } catch (Exception e){
                    e.printStackTrace();
                }
            }
        };
        t.start();
    }

    public void clearGui(){
        this.main.removeAll();
    }

    private void init() {
        this.setLayout(new GridLayout(1, 1, 3, 3));
        this.main = new JPanel();
        this.main.setLayout(new GridLayout(1, 1, 3, 3));

        this.splitPaneSqlSession = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        this.add(this.main);
    }

    private void initGui() {
        this.ganttDataByIdSqlId = new GanttDataById2(storeManager, (byte) 0); // Sql Id
        this.ganttDataByIdSessionId = new GanttDataById2(storeManager, (byte) 1); // Session Id

        this.ganttDataByIdSqlId.setIProfile(getIProfile());
        this.ganttDataByIdSessionId.setIProfile(getIProfile());
    }

    private void clearListOfGanttDataById() {
        this.ganttDataByIdSqlId.clear();
        this.ganttDataByIdSessionId.clear();
    }

    private void loadDataToJPanelsPrivate0(GanttParam ganttParamIn) {
        GanttParam ganttParamTo = new GanttParam.Builder(
                ganttParamIn.getBeginTime(), ganttParamIn.getEndTime())
                .sqlId(ganttParamIn.getSqlId())
                .sessionId(ganttParamIn.getSessionId())
                .serial(ganttParamIn.getSerial())
                .currentFileR(ganttParamIn.getCurrentFileR())
                .tsName(ganttParamIn.getTsName())
                .waitClass(waitClassG.isEmpty() ? ganttParamIn.getWaitClass() : waitClassG)
                .dbaFilesIdList(ganttParamIn.getDbaFilesIdList())
                .build();

        this.clearListOfGanttDataById();

        if (ashFilter != null && ashFilter.isActive()) {
            AshFilter.Matcher filterMatcher = ashFilter.matcher(
                    storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList());
            this.ganttDataByIdSqlId.computeGanttDataFromRaw(ganttParamTo, filteredAshLoader, filterMatcher); // Sql Id data
            this.ganttDataByIdSessionId.computeGanttDataFromRaw(ganttParamTo, filteredAshLoader, filterMatcher); // Session Id
        } else {
            this.ganttDataByIdSqlId.computeGanttDataFromBDB(ganttParamTo); // Sql Id data
            this.ganttDataByIdSessionId.computeGanttDataFromBDB(ganttParamTo); // Session Id
        }

        loadExecStats(ganttParamTo);
        this.addGanttTablesToJPanel();
    }

    /** Executions and time per execution of the selection, with the rule of the comparison (short ones: a minimum) */
    private void loadExecStats(GanttParam ganttParam) {
        execStats = null;
        execStatsTooLong = false;
        boolean collected = iProfile != null && storeManager.getConfigurationManager().getCurrentConfiguration()
                .getSqlColProfileList().stream().anyMatch(c -> c.getColName().equalsIgnoreCase("SQL_EXEC_ID"));
        if (!collected || ashFilter == null) {
            return;
        }
        long begin = (long) ganttParam.getBeginTime();
        long end = (long) ganttParam.getEndTime();
        if (end - begin > EXEC_STATS_MAX_RANGE) {
            execStatsTooLong = true;
            execStats = Collections.emptyMap();
            return;
        }
        try {
            execStats = PeriodComparison.executionsBySqlId(storeManager, iProfile, ashFilter.matcher(
                    storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList()), begin, end);
        } catch (Exception e) {
            log.error("Executions of the selection", e);
            execStats = null;
        }
    }

    /** A cell of the executions columns: text as shown, sorted by its number */
    private static final class ExecCell implements Comparable<ExecCell> {
        private final double sortKey;
        private final String text;

        ExecCell(double sortKey, String text) {
            this.sortKey = sortKey;
            this.text = text;
        }

        @Override
        public int compareTo(ExecCell o) {
            return Double.compare(sortKey, o.sortKey);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** The 3 columns of the SQL table plus Execs and Time/exec */
    private Object[][] withExecutions(Object[][] data) {
        Map<String, PeriodComparison.ExecutionStats> stats = execStats;
        java.text.DecimalFormat count = new java.text.DecimalFormat("#,##0");
        Object[][] out = new Object[data.length][];
        for (int r = 0; r < data.length; r++) {
            out[r] = Arrays.copyOf(data[r], 5);
            PeriodComparison.ExecutionStats e = stats.get(String.valueOf(data[r][1]));
            if (e == null) {
                out[r][3] = new ExecCell(-1, "—");
                out[r][4] = new ExecCell(-1, "—");
            } else if (e.isShort()) {
                out[r][3] = new ExecCell(e.getCount(), "≥ " + count.format(e.getCount()));
                out[r][4] = new ExecCell(0.5, Labels.getLabel("gui.compare.short"));
            } else {
                out[r][3] = new ExecCell(e.getCount(), count.format(e.getCount()));
                out[r][4] = new ExecCell(e.getSecondsPerExecution(), gui.compare.ComparePanel.time(e.getSecondsPerExecution()));
            }
        }
        return out;
    }

    private GanttTable getGanttDataToViewSqlId() {
        final GanttTable tableGanttSql;
        boolean withExecs = execStats != null;
        String[][] names = withExecs ? new String[][]{{columnNamesSqls[0][0], columnNamesSqls[0][1], columnNamesSqls[0][2],
                Labels.getLabel("gui.gantt.execs"), Labels.getLabel("gui.gantt.tpe")}} : columnNamesSqls;
        Object[][] rows = ganttDataByIdSqlId.getDataToGantt(3);
        tableGanttSql = new GanttTable(
                withExecs ? withExecutions(rows) : rows,
                names,
                getBasicJTableList(),
                ganttDataByIdSqlId.getListClassAndEvents());
        tableGanttSql.setRowHeightForJtable(23);
        tableGanttSql.getJXTable().setColumnControlVisible(true);

        tableGanttSql.getJXTable().getColumnExt(0).setEditable(false);
        tableGanttSql.getJXTable().getColumnExt(1).setEditable(false);
        tableGanttSql.getJXTable().getColumnExt(2).setEditable(false);
        if (withExecs) {
            String tip = Labels.getLabel(execStatsTooLong ? "gui.gantt.execs.toolong" : "gui.gantt.execs.tooltip");
            for (int c = 3; c <= 4; c++) {
                tableGanttSql.getJXTable().getColumnExt(c).setEditable(false);
                tableGanttSql.getJXTable().getColumnExt(c).setToolTipText(tip);
                ((javax.swing.table.TableRowSorter<?>) tableGanttSql.getJXTable().getRowSorter())
                        .setComparator(c, Comparator.naturalOrder());
            }
            javax.swing.table.DefaultTableCellRenderer right = new javax.swing.table.DefaultTableCellRenderer();
            right.setHorizontalAlignment(SwingConstants.RIGHT);
            tableGanttSql.getJXTable().getColumnExt(3).setCellRenderer(right);
            tableGanttSql.getJXTable().getColumnExt(4).setCellRenderer(right);
        }

        tableGanttSql.getJXTable().setCellSelectionEnabled(true);

        setDoubleClickForColSqlId(tableGanttSql.getJXTable(), 1); // Open SQL id detail for double click event

        JXTable sqlTable = tableGanttSql.getJXTable();
        SqlTextTooltip.install(sqlTable, (row, col) -> sqlTable.convertColumnIndexToModel(col) == 1
                ? String.valueOf(sqlTable.getModel().getValueAt(sqlTable.convertRowIndexToModel(row), 1)) : null);

        if (ashFilter != null) {
            JXTable t = tableGanttSql.getJXTable();
            FilterPopup.install(t, ashFilter, (row, col) -> Collections.singletonList(Collections.singletonList(
                    FilterPopup.condition(iProfile.getSqlIdColName(),
                            String.valueOf(t.getModel().getValueAt(t.convertRowIndexToModel(row), 1))))));
        }

        setTooltipAndPercent(tableGanttSql);

        return tableGanttSql;
    }

    private GanttTable getGanttDataToViewSession() {
        final GanttTable tableGanttSessions;
        tableGanttSessions = new GanttTable(
                ganttDataByIdSessionId.getDataToGantt(5),
                columnNamesSessions,
                getBasicJTableList(),
                ganttDataByIdSessionId.getListClassAndEvents());

        tableGanttSessions.setRowHeightForJtable(23);
        tableGanttSessions.getJXTable().setColumnControlVisible(true);
        tableGanttSessions.getJXTable().getColumnExt(2).setVisible(false); //Set invisible for session Serial

        tableGanttSessions.getJXTable().getColumnExt(0).setEditable(false);
        tableGanttSessions.getJXTable().getColumnExt(1).setEditable(false);
        tableGanttSessions.getJXTable().getColumnExt(2).setEditable(false);
        tableGanttSessions.getJXTable().getColumnExt(3).setEditable(false);

        tableGanttSessions.getJXTable().setCellSelectionEnabled(true);

        setDoubleClickForColSessionId(tableGanttSessions.getJXTable(), 1, 2);

        if (ashFilter != null) {
            JXTable t = tableGanttSessions.getJXTable();
            FilterPopup.install(t, ashFilter, (row, col) -> {
                int modelRow = t.convertRowIndexToModel(row);
                List<List<Map.Entry<String, String>>> entries = new ArrayList<>();
                entries.add(Arrays.asList(
                        FilterPopup.condition(iProfile.getSessColName(), String.valueOf(t.getModel().getValueAt(modelRow, 1))),
                        FilterPopup.condition(iProfile.getSerialColName(), String.valueOf(t.getModel().getValueAt(modelRow, 2)))));

                Object program = t.getModel().getValueAt(modelRow, 4);
                boolean hasProgramCol = storeManager.getConfigurationManager().getCurrentConfiguration()
                        .getSqlColProfileList().stream().anyMatch(c -> c.getColName().equalsIgnoreCase("PROGRAM"));
                if (hasProgramCol && program != null && !String.valueOf(program).isEmpty()) {
                    entries.add(Collections.singletonList(FilterPopup.condition("PROGRAM", String.valueOf(program))));
                }
                return entries;
            });
        }

        setTooltipAndPercent(tableGanttSessions);

        return tableGanttSessions;
    }

    private void addGanttTablesToJPanel() {
        // Sql
        JScrollPane leftPaneSql = new JScrollPane(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        leftPaneSql.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        leftPaneSql.setViewportView(this.getGanttDataToViewSqlId().getJXTable());
        leftPaneSql.setVerticalScrollBar(leftPaneSql.getVerticalScrollBar());

        // Session
        JScrollPane rightPaneSession = new JScrollPane(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rightPaneSession.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        rightPaneSession.setViewportView(this.getGanttDataToViewSession().getJXTable());
        rightPaneSession.setVerticalScrollBar(rightPaneSession.getVerticalScrollBar());

        splitPaneSqlSession.setLeftComponent(titled(Labels.getLabel("gui.gantt.sql"), leftPaneSql));
        splitPaneSqlSession.setRightComponent(titled(Labels.getLabel("gui.gantt.sessions"), rightPaneSession));
        splitPaneSqlSession.setDividerLocation(this.getWidth() / 2);
        splitPaneSqlSession.setOneTouchExpandable(true);

        splitPaneSqlSession.requestFocus();

        JPanel tables = new JPanel(new BorderLayout());
        tables.add(createExportBar(), BorderLayout.NORTH);
        tables.add(this.splitPaneSqlSession, BorderLayout.CENTER);

        this.main.removeAll();
        this.main.add(tables);
        this.repaint();
        this.validate();
    }

    /** Table under a blue title */
    private static JPanel titled(String title, JComponent table) {
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        head.add(gui.util.Ui.title(title));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(head, BorderLayout.NORTH);
        panel.add(table, BorderLayout.CENTER);
        return panel;
    }

    /** "Exportar CSV" menu: Top SQL or Top sessions, with the samples of each wait class */
    private JPanel createExportBar() {
        JButton export = new JButton(Labels.getLabel("gui.export.button") + " \u25BE");
        export.setToolTipText(Labels.getLabel("gui.export.tooltip"));

        JPopupMenu menu = new JPopupMenu();
        JMenuItem sql = new JMenuItem(Labels.getLabel("gui.export.topsql"));
        sql.addActionListener(e -> export(ganttDataByIdSqlId, columnNamesSqls[0], "TopSQL"));
        JMenuItem sessions = new JMenuItem(Labels.getLabel("gui.export.topsessions"));
        sessions.addActionListener(e -> export(ganttDataByIdSessionId, columnNamesSessions[0], "TopSessoes"));
        menu.add(sql);
        menu.add(sessions);
        export.addActionListener(e -> menu.show(export, 0, export.getHeight()));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(export);
        // the period the tables cover, as the Locks sub tab shows it
        if (lastGanttParamIn != null) {
            JLabel period = new JLabel(String.format(Labels.getLabel("gui.gantt.period"), gui.util.Ui.period(
                    (long) lastGanttParamIn.getBeginTime(), (long) lastGanttParamIn.getEndTime())));
            period.setForeground(gui.util.Ui.accent(period));
            bar.add(Box.createHorizontalStrut(12));
            bar.add(period);
        }
        return bar;
    }

    private void export(GanttDataById2 data, String[] columnNames, String tab) {
        if (data == null || lastGanttParamIn == null) {
            return;
        }
        List<String> groups = new ArrayList<>();
        List<Object[]> rows = data.getDataToExport(columnNames.length, groups);

        List<String> header = new ArrayList<>();
        header.add(Labels.getLabel("gui.export.col.activity"));
        header.add(Labels.getLabel("gui.export.col.samples"));
        header.addAll(Arrays.asList(columnNames).subList(1, columnNames.length));
        header.addAll(groups);

        CsvExport.save(this, CsvExport.fileName(
                storeManager.getConfigurationManager().getCurrentConfiguration().getConfigName(), tab,
                (long) lastGanttParamIn.getBeginTime(), (long) lastGanttParamIn.getEndTime()), header, rows);
    }

    private void setTooltipAndPercent(GanttTable gantttable) {
        final String textPainter = ConstantManager.TEXT_PAINTER;
        BasicPainterContext graphics = new BasicPainterContext();
        graphics.setPaint(getTextColor()); // theme text color (readable in dark mode)
        graphics.put(textPainter, new Font(null, Font.BOLD, 10));
        gantttable.getDrawingContext().put(textPainter,
                ContextResources.GRAPHICS_CONTEXT, graphics);
    }

    private BasicJTableList getBasicJTableList() {
        BasicJTableList tableListSqls = new BasicJTableList();
        {
            BasicComponentContext componentContext = new BasicComponentContext();
            ToolTipState state = (event, cellState) -> {
                DrawingState drawing = cellState.getDrawing();
                Object key = drawing != null ? drawing.getValueAt(event.getPoint()) : null;
                if (key == null)
                    return "";
                return key.toString();
            };
            componentContext.put(ComponentResources.TOOLTIP_STATE, state);
            tableListSqls.setRendererComponentContext(componentContext);
        }
        return tableListSqls;
    }


    public void setDoubleClickForColSqlId(JXTable table, int columnIndex){
        table.addMouseListener(new MouseAdapter(){
            public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() == 2)
                {
                    final Object valueAt = table.getModel().getValueAt(table.getSelectedRow(), columnIndex);
                    new SqlDetail(jFrame, new GanttParam.Builder(ganttParam.getBeginTime(), ganttParam.getEndTime()).build(),
                            (String) valueAt, storeManager, getFromRemoteAndStore, colorManager);
                }
            }
        });
    }

    public void setDoubleClickForColSessionId(JXTable table, int cIndexSess, int cIndexSerial){
        table.addMouseListener(new MouseAdapter(){
            public void mouseClicked(MouseEvent e)
            {
                if (e.getClickCount() == 2)
                {
                    final Object valueAtSessId = table.getModel().getValueAt(table.getSelectedRow(), cIndexSess);
                    final Object valueAtSerial = table.getModel().getValueAt(table.getSelectedRow(), cIndexSerial);

                    new SessionDetail(jFrame,
                            new GanttParam.Builder(ganttParam.getBeginTime(), ganttParam.getEndTime())
                                    .sessionId((String) valueAtSessId).serial((String) valueAtSerial).build(),
                            storeManager, getFromRemoteAndStore, colorManager);
                }
            }
        });
    }

    private void setTimeRange(){
        if (!isHistory){
            double start = getFromRemoteAndStore.getCurrServerTime() - ConstantManager.CURRENT_WINDOW;
            double end = getFromRemoteAndStore.getCurrServerTime();

            ganttParam = new GanttParam.Builder(start, end).build();
        }
    }

    private static Color getTextColor() {
        Color color = UIManager.getColor("Table.foreground");
        return color != null ? color : Color.BLACK;
    }
}
