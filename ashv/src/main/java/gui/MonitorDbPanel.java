package gui;

import com.github.lgooddatepicker.components.DatePickerSettings;
import com.github.lgooddatepicker.components.DateTimePicker;
import com.github.lgooddatepicker.components.TimePickerSettings;
import config.Labels;
import core.filter.AshFilter;
import core.filter.FilterPresets;
import core.filter.FilteredAshLoader;
import core.manager.ColorManager;
import core.manager.ConstantManager;
import core.parameter.ParameterBuilder;
import core.processing.GetFromRemoteAndStore;
import gui.chart.ChartDatasetManager;
import gui.chart.GroupByChart;
import gui.chart.panel.NameChartDataset;
import gui.compare.ComparePanel;
import gui.custom.TabIcon;
import gui.filter.FilterBar;
import gui.snapshot.SnapshotPanel;
import remote.RemoteDBManager;
import gui.gantt.MonitorGantt2;
import core.lock.ObjectNames;
import core.sqltext.SqlTexts;
import gui.lock.LockTreePanel;
import gui.table.RawDataTable;
import gui.util.ProgressBarUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jfree.chart.util.GanttParam;
import config.profile.ConnProfile;
import profile.IProfile;
import store.StoreManager;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.*;
import javax.swing.border.EtchedBorder;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Singleton
public class MonitorDbPanel {
    private BasicFrame jFrame;
    private ColorManager colorManager;
    private StoreManager storeManager;
    private GetFromRemoteAndStore getFromRemoteAndStore;

    private ChartDatasetManager chartDatasetManager;
    private MainTabbedPane mainTabbedPane;
    private AshFilter ashFilter;
    private FilteredAshLoader filteredAshLoader;
    private FilterPresets filterPresets;
    private RemoteDBManager remoteDBManager;

    private JSplitPane topActivitySplitPane;
    private JSplitPane detailSplitPane;
    private JTabbedPane mainGanttAndRaw;
    private JTabbedPane detailGanttAndRaw;

    private JPanel controlMainDetailHistory;

    private MonitorGantt2 monitorGantt20;
    private RawDataTable rawDataTable20;
    private LockTreePanel lockTreePanel;
    private ComparePanel comparePanel;
    private ObjectNames objectNames;

    @Getter @Setter private ConnProfile connProfile;
    @Getter @Setter private IProfile iProfile;

    @Getter @Setter private HistoryPanel historyPanel;

    @Inject
    public MonitorDbPanel(BasicFrame jFrame,
                          ColorManager colorManager,
                          StoreManager storeManager,
                          GetFromRemoteAndStore getFromRemoteAndStore,
                          @Named("mainTabPane") MainTabbedPane mainTabbedPane,
                          ChartDatasetManager chartDatasetManager,
                          AshFilter ashFilter,
                          FilteredAshLoader filteredAshLoader,
                          FilterPresets filterPresets,
                          RemoteDBManager remoteDBManager) {
        this.jFrame = jFrame;
        this.colorManager = colorManager;
        this.storeManager = storeManager;
        this.getFromRemoteAndStore = getFromRemoteAndStore;
        this.mainTabbedPane = mainTabbedPane;
        this.chartDatasetManager = chartDatasetManager;
        this.ashFilter = ashFilter;
        this.filteredAshLoader = filteredAshLoader;
        this.filterPresets = filterPresets;
        this.remoteDBManager = remoteDBManager;
        SqlTexts.configure(storeManager, remoteDBManager);

        this.ashFilter.addListener(() -> ProgressBarUtil.runProgressDialog(
                getFromRemoteAndStore::refreshMainChart, jFrame, Labels.getLabel("gui.filter.applying")));
    }

    public void initialize() {
        try {
            storeManager.setUpBDBAndDAO(connProfile.getConnName());
        } catch (IOException e) {
            e.printStackTrace();
        }

        initializeChartDataset();
    }

    public void initializeGui(){
        topActivitySplitPane = new JSplitPane();
        topActivitySplitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        topActivitySplitPane.setOneTouchExpandable(true);
        topActivitySplitPane.setDividerSize(10);
        topActivitySplitPane.setDividerLocation(300);

        mainGanttAndRaw = new JTabbedPane();
        mainGanttAndRaw.add("Top sql & sessions", chartDatasetManager.getMainNameChartDataset().getMonitorGantt2());
        mainGanttAndRaw.add("Raw data", chartDatasetManager.getMainNameChartDataset().getRawDataTable());
        mainGanttAndRaw.add("ASH Report", chartDatasetManager.getMainNameChartDataset().getAshReport());
        objectNames = new ObjectNames(remoteDBManager, connProfile.getProfileName());
        lockTreePanel = new LockTreePanel(storeManager, ashFilter, getFromRemoteAndStore::getMainChartRange,
                objectNames, true);
        chartDatasetManager.getMainNameChartDataset().getStackChartPanel().addChartListenerReleaseMouse(lockTreePanel);
        chartDatasetManager.getMainNameChartDataset().getStackChartPanel().addChartListenerReleaseMouse(getComparePanel());
        mainGanttAndRaw.add(Labels.getLabel("gui.lock.tab"), lockTreePanel);

        FilterBar filterBar = newFilterBar(getFromRemoteAndStore::getMainChartRange);
        JPanel topActivityNorthPanel = new JPanel(new BorderLayout());
        topActivityNorthPanel.add(createGroupByPanel(), BorderLayout.WEST);
        topActivityNorthPanel.add(filterBar, BorderLayout.CENTER);
        JPanel reconnectPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 3));
        reconnectPanel.add(createReconnectButton());
        topActivityNorthPanel.add(reconnectPanel, BorderLayout.EAST);

        JPanel topActivityChartPanel = new JPanel(new BorderLayout());
        topActivityChartPanel.add(topActivityNorthPanel, BorderLayout.NORTH);
        topActivityChartPanel.add(chartCards, BorderLayout.CENTER);

        topActivitySplitPane.add(topActivityChartPanel, JSplitPane.TOP);
        topActivitySplitPane.add(mainGanttAndRaw, JSplitPane.BOTTOM);

        chartDatasetManager.getMainNameChartDataset().getMonitorGantt2().setHistory(false);

        loadDetailChart();
        loadHistory();
    }

    public void adddGui(){
        mainTabbedPane.addTab("Top Activity", new TabIcon(TabIcon.Type.ACTIVITY), topActivitySplitPane);
        // Detail tab is hidden by default (Top Activity "group by" covers it); -Dashv.detailtab=true shows it
        if (Boolean.getBoolean("ashv.detailtab")) {
            mainTabbedPane.add("Detail", detailSplitPane);
        }
        addGuiHistory();
        mainTabbedPane.addTab(Labels.getLabel("gui.snapshot.tab.sessions"), new TabIcon(TabIcon.Type.SESSIONS),
                new SnapshotPanel(remoteDBManager, connProfile.getProfileName(), SnapshotPanel.Page.SESSIONS));
        mainTabbedPane.addTab(Labels.getLabel("gui.snapshot.tab.resources"), new TabIcon(TabIcon.Type.RESOURCES),
                new SnapshotPanel(remoteDBManager, connProfile.getProfileName(), SnapshotPanel.Page.RESOURCES));
    }

    public void addGuiHistory(){
        mainTabbedPane.addTab("History", new TabIcon(TabIcon.Type.HISTORY), controlMainDetailHistory);
        mainTabbedPane.addTab(Labels.getLabel("gui.compare.tab"), new TabIcon(TabIcon.Type.COMPARE), getComparePanel());
    }

    /** "Comparar" tab, period B starts as the History period (or the Top Activity chart) */
    private ComparePanel getComparePanel() {
        if (comparePanel == null) {
            comparePanel = new ComparePanel(storeManager, ashFilter, filteredAshLoader, colorManager, this::getHistoryRange);
            comparePanel.setFilterBar(newFilterBar(comparePanel::getFilterRange));
        }
        return comparePanel;
    }

    // Columns for "group by" (shown when collected by the connection)
    private static final String[] GROUP_BY_COLUMNS = {
            "SQL_ID", "MODULE", "ACTION", "PROGRAM", "USERNAME", "OSUSER", "MACHINE", "SESSION_ID",
            "SERVICE_NAME", "CLIENT_IDENTIFIER", "CLIENT_INFO", "EVENT", "COMMAND", "SESSION_TYPE",
            "BLOCKING_SESSION", "FINAL_BLOCKING_SESSION", "PREV_SQL_ID", "SQL_HASH_VALUE",
            "PLSQL_ENTRY_OBJECT_ID", "CURRENT_OBJ#"};

    private JPanel chartCards;

    /**
     * "Group by" combo: wait class (original chart) or a raw data column (grouped chart)
     */
    private JPanel createGroupByPanel() {
        NameChartDataset main = chartDatasetManager.getMainNameChartDataset();

        GroupByChart groupByChart = new GroupByChart(storeManager, colorManager, ashFilter, filteredAshLoader,
                getFromRemoteAndStore::getMainChartXValues);
        groupByChart.getStackChartPanel().addChartListenerReleaseMouse(main.getMonitorGantt2());
        groupByChart.getStackChartPanel().addChartListenerReleaseMouse(main.getRawDataTable());
        groupByChart.getStackChartPanel().addChartListenerReleaseMouse(main.getAshReport());
        groupByChart.getStackChartPanel().addChartListenerReleaseMouse(lockTreePanel);
        groupByChart.getStackChartPanel().addChartListenerReleaseMouse(getComparePanel());
        getFromRemoteAndStore.setMainChartListener(groupByChart::refresh);

        CardLayout cardLayout = new CardLayout();
        chartCards = new JPanel(cardLayout);
        chartCards.add(main.getStackChartPanel(), "waitclass");
        chartCards.add(groupByChart.getStackChartPanel(), "groupby");

        return newGroupByPanel(selected -> {
            if (selected == null) {
                groupByChart.setColName(null);
                cardLayout.show(chartCards, "waitclass");
            } else {
                groupByChart.setColName(selected);
                ProgressBarUtil.runProgressDialog(() -> groupByChart.refresh(true), jFrame,
                        Labels.getLabel("gui.groupby.loading"));
                cardLayout.show(chartCards, "groupby");
            }
        });
    }

    /**
     * "Agrupar por" combo with the collected columns; null is passed for wait class
     */
    private JPanel newGroupByPanel(java.util.function.Consumer<String> onChange) {
        java.util.List<String> available = new java.util.ArrayList<>();
        java.util.List<config.profile.SqlColProfile> cols =
                storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        if (cols != null) {
            cols.forEach(c -> available.add(c.getColName()));
        }

        JComboBox<String> groupByBox = new JComboBox<>();
        String waitClassItem = Labels.getLabel("gui.groupby.waitclass");
        groupByBox.addItem(waitClassItem);
        for (String col : GROUP_BY_COLUMNS) {
            if (available.contains(col)) {
                groupByBox.addItem(col);
            }
        }
        groupByBox.setMaximumRowCount(GROUP_BY_COLUMNS.length + 1);
        groupByBox.setToolTipText(Labels.getLabel("gui.groupby.tooltip"));

        groupByBox.addActionListener(e -> {
            String selected = (String) groupByBox.getSelectedItem();
            onChange.accept(selected == null || selected.equals(waitClassItem) ? null : selected);
        });

        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        panel.add(new JLabel(Labels.getLabel("gui.groupby.label")));
        panel.add(groupByBox);
        panel.add(new JSeparator(SwingConstants.VERTICAL));
        return panel;
    }

    /**
     * New connections for the ASH collection, after a network drop (the collection does not recover alone)
     */
    private JButton createReconnectButton() {
        JButton button = new JButton(Labels.getLabel("gui.reconnect.button"));
        button.setToolTipText(Labels.getLabel("gui.reconnect.tooltip"));
        button.addActionListener(e -> {
            button.setEnabled(false);
            button.setText(Labels.getLabel("gui.reconnect.running"));
            Thread t = new Thread(() -> {
                String error = getFromRemoteAndStore.reconnect();
                SwingUtilities.invokeLater(() -> {
                    button.setText(Labels.getLabel("gui.reconnect.button"));
                    button.setEnabled(true);
                    if (error == null) {
                        JOptionPane.showMessageDialog(button, Labels.getLabel("gui.reconnect.done"),
                                Labels.getLabel("gui.reconnect.button"), JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(button, Labels.getLabel("gui.reconnect.error") + "\n" + error,
                                Labels.getLabel("gui.reconnect.button"), JOptionPane.ERROR_MESSAGE);
                    }
                });
            }, "reconnect");
            t.setDaemon(true);
            t.start();
        });
        return button;
    }

    private FilterBar newFilterBar(java.util.function.Supplier<long[]> rangeSupplier) {
        return new FilterBar(ashFilter, filteredAshLoader, filterPresets, storeManager, rangeSupplier);
    }

    private long[] getHistoryRange() {
        GanttParam ganttParam = historyPanel == null ? null : historyPanel.getLastGanttParam();
        return ganttParam == null
                ? getFromRemoteAndStore.getMainChartRange()
                : new long[]{(long) ganttParam.getBeginTime(), (long) ganttParam.getEndTime()};
    }

    private void initializeChartDataset(){
        chartDatasetManager.setConnProfile(this.connProfile);
        chartDatasetManager.initialize();
    }

    public void setProfile(IProfile iProfile0){
        this.iProfile = iProfile0;
        this.monitorGantt20.setIProfile(iProfile0);
        this.historyPanel.setIProfile(iProfile0);
        this.rawDataTable20.setIProfile(iProfile0);
    }

    private void loadDetailChart(){
        Map<String, JRadioButton> mapRadioButton = new LinkedHashMap<>();

        detailSplitPane = new JSplitPane();
        detailSplitPane.setOrientation(JSplitPane.VERTICAL_SPLIT);
        detailSplitPane.setOneTouchExpandable(true);
        detailSplitPane.setDividerSize(10);
        detailSplitPane.setDividerLocation(300);

        JPanel controlStackedChartInner = new JPanel();
        controlStackedChartInner.setLayout(new BorderLayout());

        AtomicInteger atomicInt = new AtomicInteger(0);
        MigLayout migLayout = new MigLayout("", "[fill, grow]", "[fill, grow]");
        JPanel jPanelStackedCharts = new JPanel(migLayout);

        final JToolBar buttonPanel;
        final ButtonGroup bg = new ButtonGroup();
        buttonPanel = new JToolBar("PanelButton");
        buttonPanel.setFloatable(false);
        buttonPanel.setBorder(new EtchedBorder());

        monitorGantt20 = new MonitorGantt2(jFrame, storeManager, getFromRemoteAndStore, colorManager);
        monitorGantt20.setHistory(false);
        rawDataTable20 = new RawDataTable(jFrame, storeManager);
        monitorGantt20.setAshFilter(ashFilter);
        rawDataTable20.setAshFilter(ashFilter);

        detailGanttAndRaw = new JTabbedPane();

        detailGanttAndRaw.add("Top sql & sessions", monitorGantt20);
        detailGanttAndRaw.add("Raw data", rawDataTable20);

        chartDatasetManager.getNameChartDatasetDetail().stream().forEach(e -> {
            String strChartPanel = "cell 0 0, hidemode 3";
            JRadioButton aRadio = new JRadioButton(e.getName());

            aRadio.addItemListener(evt -> {
                AbstractButton sel = (AbstractButton)evt.getItemSelectable();
                if(evt.getStateChange() == ItemEvent.SELECTED){
                    if (sel.getText().equalsIgnoreCase(e.getName())){
                        chartDatasetManager.getNameChartDatasetDetail()
                                .stream()
                                .forEach(l -> {
                                    l.getStackChartPanel().getStackedChart().getChartPanel().setVisible(false);
                                    e.getMonitorGantt2().setVisible(false);
                                });
                        e.getStackChartPanel().getStackedChart().getChartPanel().setVisible(true);

                        monitorGantt20.setWaitClassG(e.getName());
                        rawDataTable20.setWaitClassValue(e.getName());

                        monitorGantt20.clearGui();
                        monitorGantt20.repaint();
                    }
                }
            });

            mapRadioButton.put(e.getName(), aRadio);

            // bug auto color
            //e.getStackChartPanel().initialize();
            // bug auto color

            e.getStackChartPanel().getStackedChart().setChartTitle(e.getName());
            e.getStackChartPanel().getStackedChart().getChartPanel().setVisible(false);
            jPanelStackedCharts.add(e.getStackChartPanel().getStackedChart().getChartPanel(), strChartPanel);

            e.getStackChartPanel().addChartListenerReleaseMouse(monitorGantt20);
            e.getStackChartPanel().addChartListenerReleaseMouse(rawDataTable20);

            atomicInt.getAndIncrement();
        });

        if (atomicInt.get() != 0) {
            mapRadioButton.entrySet().stream().forEach(e -> {
                bg.add(e.getValue());
                buttonPanel.add(e.getValue());
            });
            mapRadioButton.entrySet().stream().findFirst().get().getValue().doClick();

            JPanel detailNorthPanel = new JPanel(new BorderLayout());
            detailNorthPanel.add(newFilterBar(getFromRemoteAndStore::getMainChartRange), BorderLayout.NORTH);
            detailNorthPanel.add(buttonPanel, BorderLayout.CENTER);

            controlStackedChartInner.add(detailNorthPanel, BorderLayout.NORTH);
            controlStackedChartInner.add(jPanelStackedCharts, BorderLayout.CENTER);

            detailSplitPane.add(controlStackedChartInner, JSplitPane.TOP);
            detailSplitPane.add(detailGanttAndRaw, JSplitPane.BOTTOM);
        }
    }

    public void loadHistory(){
        controlMainDetailHistory = new JPanel();
        controlMainDetailHistory.setLayout(new BorderLayout());

        MigLayout lm0 = new MigLayout("", "[][]", "[]");
        JPanel mainStartEndDateTimePicker = new JPanel(lm0);

        MigLayout lm1 = new MigLayout("fillx", "[1lp][1lp][1lp][1lp][1lp][1lp][1lp][1lp]");
        JPanel startEndDateTimePicker = new JPanel(lm1);

        historyPanel = new HistoryPanel(jFrame, colorManager, storeManager, getFromRemoteAndStore, chartDatasetManager);
        historyPanel.setAshFilter(ashFilter, filteredAshLoader);
        historyPanel.setObjectNames(objectNames);

        Map<String, JRadioButton> mapRadioButton = new LinkedHashMap<>();

        final JToolBar buttonPanel;
        final ButtonGroup bg = new ButtonGroup();
        buttonPanel = new JToolBar("PanelButtonHistory");
        buttonPanel.setFloatable(false);
        buttonPanel.setBorder(new EtchedBorder());

        Custom custom = new Custom(buttonPanel);
        custom.initialize();

        JRadioButton aRadioHour8 = new JRadioButton(String.valueOf(ConstantManager.History.Hour8));
        aRadioHour8.addItemListener(evt -> {
            AbstractButton sel = (AbstractButton)evt.getItemSelectable();
            if(evt.getStateChange() == ItemEvent.SELECTED){
                if (sel.getText().equalsIgnoreCase(String.valueOf(ConstantManager.History.Hour8))){
                    ProgressBarUtil.runProgressDialog(()
                            -> historyPanel.initializeGui(getGanttParam(8)), jFrame, "Loading data ..");
                    custom.setEnabled(false);
                }
            }
        });

        JRadioButton aRadioHour12 = new JRadioButton(String.valueOf(ConstantManager.History.Hour12));
        aRadioHour12.addItemListener(evt -> {
            AbstractButton sel = (AbstractButton)evt.getItemSelectable();
            if(evt.getStateChange() == ItemEvent.SELECTED){
                if (sel.getText().equalsIgnoreCase(String.valueOf(ConstantManager.History.Hour12))){
                    ProgressBarUtil.runProgressDialog(()
                            -> historyPanel.initializeGui(getGanttParam(12)), jFrame, "Loading data ..");
                    custom.setEnabled(false);
                }
            }
        });

        JRadioButton aRadioDay1 = new JRadioButton(String.valueOf(ConstantManager.History.Day1));
        aRadioDay1.addItemListener(evt -> {
            AbstractButton sel = (AbstractButton)evt.getItemSelectable();
            if(evt.getStateChange() == ItemEvent.SELECTED){
                if (sel.getText().equalsIgnoreCase(String.valueOf(ConstantManager.History.Day1))){
                    ProgressBarUtil.runProgressDialog(()
                            -> historyPanel.initializeGui(getGanttParam(24)), jFrame, "Loading data ..");
                    custom.setEnabled(false);
                }
            }
        });

        JRadioButton aRadioWeek = new JRadioButton(String.valueOf(ConstantManager.History.Week));
        aRadioWeek.addItemListener(evt -> {
            AbstractButton sel = (AbstractButton)evt.getItemSelectable();
            if(evt.getStateChange() == ItemEvent.SELECTED){
                if (sel.getText().equalsIgnoreCase(String.valueOf(ConstantManager.History.Week))){
                    ProgressBarUtil.runProgressDialog(()
                            -> historyPanel.initializeGui(getGanttParam(168)), jFrame, "Loading data ..");
                    custom.setEnabled(false);
                }
            }
        });

        JRadioButton aRadioMonth = new JRadioButton(String.valueOf(ConstantManager.History.Month));
        aRadioMonth.addItemListener(evt -> {
            AbstractButton sel = (AbstractButton)evt.getItemSelectable();
            if(evt.getStateChange() == ItemEvent.SELECTED){
                if (sel.getText().equalsIgnoreCase(String.valueOf(ConstantManager.History.Month))){
                    ProgressBarUtil.runProgressDialog(()
                            -> historyPanel.initializeGui(getGanttParam(740)), jFrame, "Loading data ..");
                    custom.setEnabled(false);
                }
            }
        });

        JRadioButton aRadioCustom = new JRadioButton(String.valueOf(ConstantManager.History.Custom));
        aRadioCustom.addItemListener(evt -> {
            AbstractButton sel = (AbstractButton)evt.getItemSelectable();
            if(evt.getStateChange() == ItemEvent.SELECTED){
                if (sel.getText().equalsIgnoreCase(String.valueOf(ConstantManager.History.Custom))){
                    custom.setEnabled(true);
                }
            }
        });

        mapRadioButton.put(String.valueOf(ConstantManager.History.Hour8), aRadioHour8);
        mapRadioButton.put(String.valueOf(ConstantManager.History.Hour12), aRadioHour12);
        mapRadioButton.put(String.valueOf(ConstantManager.History.Day1), aRadioDay1);
        mapRadioButton.put(String.valueOf(ConstantManager.History.Week), aRadioWeek);
        mapRadioButton.put(String.valueOf(ConstantManager.History.Month), aRadioMonth);
        mapRadioButton.put(String.valueOf(ConstantManager.History.Custom), aRadioCustom);

        mapRadioButton.entrySet().stream().forEach(e ->{
            bg.add(e.getValue());
            buttonPanel.add(e.getValue());
        });

        custom.fillButtonPanel();

        mapRadioButton.entrySet().stream().findFirst()
                .filter(e -> e.getKey().equalsIgnoreCase(String.valueOf(ConstantManager.History.Hour8)))
                .get().getValue().doClick();

        startEndDateTimePicker.add(new JLabel(), ""); // empty
        startEndDateTimePicker.add(buttonPanel, "gap 1");
        startEndDateTimePicker.add(new JLabel(), ""); // empty

        startEndDateTimePicker.add(new JLabel(Labels.getLabel("gui.history.date.start")), "");
        startEndDateTimePicker.add(custom.getDtpStart(), "gap 1");
        startEndDateTimePicker.add(new JLabel(), ""); // empty
        startEndDateTimePicker.add(new JLabel(Labels.getLabel("gui.history.date.end")), "");
        startEndDateTimePicker.add(custom.getDtpEnd(), "gap 1");
        startEndDateTimePicker.add(new JLabel(), ""); // empty
        startEndDateTimePicker.add(custom.getLoadButton(), "gap 1");

        mainStartEndDateTimePicker.add(startEndDateTimePicker, "span 2, wmin 100");

        JPanel historyNorthPanel = new JPanel(new BorderLayout());
        historyNorthPanel.add(mainStartEndDateTimePicker/*buttonPanel*/, BorderLayout.NORTH);
        JPanel historyFilterPanel = new JPanel(new BorderLayout());
        historyFilterPanel.add(newGroupByPanel(historyPanel::setGroupBy), BorderLayout.WEST);
        historyFilterPanel.add(newFilterBar(this::getHistoryRange), BorderLayout.CENTER);
        historyNorthPanel.add(historyFilterPanel, BorderLayout.SOUTH);

        controlMainDetailHistory.add(historyNorthPanel, BorderLayout.NORTH);
        controlMainDetailHistory.add(historyPanel, BorderLayout.CENTER);

        controlMainDetailHistory.revalidate();
    }

    private GanttParam getGanttParam(int numberOfHour){
        ParameterBuilder param = new ParameterBuilder.Builder(0L, Long.MAX_VALUE).build();
        long timeStampMillis = storeManager.getDatabaseDAO().getMax(param);

        long currServerOrClientTime = getFromRemoteAndStore.getCurrServerTime() == 0L ?
                timeStampMillis : getFromRemoteAndStore.getCurrServerTime();

        double start = currServerOrClientTime - ConstantManager.CURRENT_WINDOW * numberOfHour;
        double end = currServerOrClientTime;

        return new GanttParam.Builder(start, end).build();
    }

    class Custom {
        private JToolBar buttonPanel;
        @Getter private JButton loadButton;
        @Getter private DateTimePicker dtpStart;
        @Getter private DateTimePicker dtpEnd;

        public Custom(JToolBar buttonPanel) {
            this.buttonPanel = buttonPanel;
            this.loadButton = new JButton(Labels.getLabel("gui.history.date.load.data.button"));
        }

        public void initialize(){
            DatePickerSettings dateSettingsStart = new DatePickerSettings();
            DatePickerSettings dateSettingsEnd = new DatePickerSettings();
            TimePickerSettings timeSettingsStart = new TimePickerSettings();
            TimePickerSettings timeSettingsEnd = new TimePickerSettings();
            dateSettingsStart.setAllowEmptyDates(false);
            timeSettingsStart.setAllowEmptyTimes(false);
            dateSettingsEnd.setAllowEmptyDates(false);
            timeSettingsEnd.setAllowEmptyTimes(false);

            dtpStart = new DateTimePicker(dateSettingsStart, timeSettingsStart);
            dtpEnd = new DateTimePicker(dateSettingsEnd, timeSettingsEnd);

            loadButton = new JButton(Labels.getLabel("gui.history.date.load.data.button"));
            loadButton.addActionListener(e -> loadDataCustom());
        }

        public void fillButtonPanel(){
            this.buttonPanel.add(dtpStart);
            this.buttonPanel.add(dtpEnd);
            this.buttonPanel.add(loadButton);
        }

        public void setEnabled(boolean flag){
            dtpStart.setEnabled(flag);
            dtpEnd.setEnabled(flag);
            loadButton.setEnabled(flag);
        }

        public void loadDataCustom(){
            ProgressBarUtil.runProgressDialog(()
                    -> historyPanel.initializeGui(getParameters()), jFrame, "Loading data ..");
        }

        private GanttParam getParameters(){
            LocalDateTime beginDt = dtpStart.getDateTimePermissive();
            LocalDateTime endDt = dtpEnd.getDateTimePermissive();

            long start = beginDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long end = endDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

            return new GanttParam.Builder(start, end).build();
        }
    }

}
