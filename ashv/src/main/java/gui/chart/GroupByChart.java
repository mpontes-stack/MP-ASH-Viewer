package gui.chart;

import config.Labels;
import core.filter.AshFilter;
import core.filter.FilteredAshLoader;
import core.manager.ColorManager;
import core.manager.ConstantManager;
import gui.chart.panel.StackChartPanel;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartMouseEvent;
import org.jfree.chart.ChartMouseListener;
import org.jfree.chart.entity.XYItemEntity;
import store.StoreManager;

/**
 * Main chart grouped by a column of the raw data (MODULE, SQL_ID, MACHINE...) instead of wait class:
 * top values of the visible window plus "Others"
 */
@Slf4j
public class GroupByChart {
    private static final int TOP_VALUES = 10;
    private static final int MAX_LEGEND_TEXT = 40;

    // Distinct colors readable on the gray plot background
    private static final Color[] PALETTE = {
            new Color(78, 121, 167), new Color(242, 142, 43), new Color(225, 87, 89), new Color(118, 183, 178),
            new Color(89, 161, 79), new Color(237, 201, 72), new Color(176, 122, 161), new Color(255, 157, 167),
            new Color(156, 117, 95), new Color(47, 75, 124)};
    private static final Color OTHERS_COLOR = new Color(150, 150, 150);
    private static final Color EMPTY_COLOR = new Color(225, 225, 225);

    private final StoreManager storeManager;
    private final AshFilter ashFilter;
    private final FilteredAshLoader filteredAshLoader;
    private final Supplier<List<Double>> xValuesSupplier;
    private final long step;

    @Getter private final StackChartPanel stackChartPanel;
    private final CategoryTableXYDatasetRDA dataset;

    @Getter private volatile String colName;
    private final Map<String, Color> colors = new HashMap<>();
    private final Map<String, String> valueBySeriesName = new HashMap<>();
    private String lastSignature = "";

    private final String othersLabel = Labels.getLabel("gui.groupby.others");
    private final String emptyLabel = Labels.getLabel("gui.filter.empty");

    /**
     * Chart for the live Top Activity window (points every CURRENT_WINDOW / MAX_POINT_PER_GRAPH ms)
     */
    public GroupByChart(StoreManager storeManager, ColorManager colorManager, AshFilter ashFilter,
                        FilteredAshLoader filteredAshLoader, Supplier<List<Double>> xValuesSupplier) {
        this(storeManager, colorManager, ashFilter, filteredAshLoader, xValuesSupplier,
                ConstantManager.CURRENT_WINDOW / ConstantManager.MAX_POINT_PER_GRAPH, 12);
    }

    /**
     * @param step milliseconds between chart points (each point sums the samples of the next {@code step} ms)
     */
    public GroupByChart(StoreManager storeManager, ColorManager colorManager, AshFilter ashFilter,
                        FilteredAshLoader filteredAshLoader, Supplier<List<Double>> xValuesSupplier,
                        long step, int legendFontSize) {
        this.storeManager = storeManager;
        this.ashFilter = ashFilter;
        this.filteredAshLoader = filteredAshLoader;
        this.xValuesSupplier = xValuesSupplier;
        this.step = step;

        this.dataset = new CategoryTableXYDatasetRDA();
        this.stackChartPanel = new StackChartPanel(Labels.getLabel("chart.main.name"), colorManager);
        this.stackChartPanel.setXyDatasetRDA(dataset);
        this.stackChartPanel.setXAxisLabel(" ");
        this.stackChartPanel.setLegendFontSize(legendFontSize);
        this.stackChartPanel.initialize();

        this.stackChartPanel.getStackedChart().getChartPanel().addChartMouseListener(new ChartMouseListener() {
            @Override
            public void chartMouseClicked(ChartMouseEvent event) {
                if (event.getTrigger().getClickCount() == 2 && event.getEntity() instanceof XYItemEntity) {
                    int series = ((XYItemEntity) event.getEntity()).getSeriesIndex();
                    showFilterMenu(String.valueOf(dataset.getSeriesKey(series)), event);
                }
            }

            @Override
            public void chartMouseMoved(ChartMouseEvent event) { }
        });
    }

    /**
     * @param colName column to group by, null to disable
     */
    public void setColName(String colName) {
        this.colName = colName;
        this.lastSignature = "";
    }

    public boolean isActive() {
        return colName != null;
    }

    /**
     * Rebuild the chart when the main chart has new points (live update) or always when forced
     */
    public void refresh(boolean force) {
        String col = colName;
        if (col == null) {
            return;
        }

        // read the points before locking: the supplier synchronizes with the data collection
        List<Double> xValues = xValuesSupplier.get();
        synchronized (this) {
            refresh(col, xValues, force);
        }
    }

    private void refresh(String col, List<Double> xValues, boolean force) {
        String signature = col + "|" + xValues.size() + "|" + (xValues.isEmpty() ? "" : xValues.get(xValues.size() - 1));
        if (!force && signature.equals(lastSignature)) {
            return;
        }
        lastSignature = signature;

        try {
            rebuild(col, xValues);
        } catch (Exception e) {
            log.error("Unable to group chart by " + col + ": " + e.getMessage());
        }
    }

    private void rebuild(String col, List<Double> xValues) {
        long range = step;
        AshFilter.Matcher matcher = ashFilter.matcher(
                storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList());

        Map<Double, Map<String, Integer>> byPoint = filteredAshLoader.countByValue(matcher, col, xValues, range);

        // Top values of the window
        Map<String, Integer> totals = new HashMap<>();
        byPoint.values().forEach(m -> m.forEach((v, c) -> totals.merge(v, c, Integer::sum)));
        List<String> top = totals.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(TOP_VALUES)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        boolean hasOthers = totals.size() > top.size();

        // Series names in stack order (largest at the bottom)
        LinkedHashMap<String, String> seriesByValue = new LinkedHashMap<>();
        Map<String, String> valueByName = new HashMap<>();
        top.forEach(v -> {
            String name = v.isEmpty() ? emptyLabel : v;
            if (name.length() > MAX_LEGEND_TEXT) {
                name = name.substring(0, MAX_LEGEND_TEXT - 3) + "...";
                while (valueByName.containsKey(name)) {
                    name = name + " "; // keep truncated names unique
                }
            }
            seriesByValue.put(v, name);
            valueByName.put(name, v);
        });
        valueBySeriesName.clear();
        valueBySeriesName.putAll(valueByName);
        List<String> seriesNames = new ArrayList<>(seriesByValue.values());
        if (hasOthers) {
            seriesNames.add(othersLabel);
        }

        assignColors(seriesNames);

        double seconds = range / 1000d;
        dataset.clearAll();
        for (Double x : xValues) {
            Map<String, Integer> byValue = byPoint.getOrDefault(x, Collections.emptyMap());
            int others = byValue.entrySet().stream()
                    .filter(e -> !seriesByValue.containsKey(e.getKey()))
                    .mapToInt(Map.Entry::getValue).sum();

            seriesByValue.forEach((value, name) ->
                    dataset.setSeriesValueNoNotify(x, byValue.getOrDefault(value, 0) / seconds, name));
            if (hasOthers) {
                dataset.setSeriesValueNoNotify(x, others / seconds, othersLabel);
            }
        }

        stackChartPanel.getStackedChart().setSeriesPaints(name -> colors.getOrDefault(name, OTHERS_COLOR));
        stackChartPanel.getStackedChart().setChartTitle(Labels.getLabel("gui.groupby.title") + " " + col);
        dataset.notifyChanged();
    }

    /**
     * Keep the color of values already shown, give free palette colors to new ones
     */
    private void assignColors(List<String> seriesNames) {
        colors.keySet().retainAll(seriesNames);
        List<Color> used = new ArrayList<>(colors.values());
        List<Color> free = new ArrayList<>(Arrays.asList(PALETTE));
        free.removeAll(used);

        int next = 0;
        for (String name : seriesNames) {
            if (colors.containsKey(name)) {
                continue;
            }
            if (name.equals(othersLabel)) {
                colors.put(name, OTHERS_COLOR);
            } else if (name.equals(emptyLabel)) {
                colors.put(name, EMPTY_COLOR);
            } else {
                colors.put(name, free.isEmpty() ? PALETTE[next++ % PALETTE.length] : free.remove(0));
            }
        }
    }

    private void showFilterMenu(String seriesName, ChartMouseEvent event) {
        String col = colName;
        if (col == null || seriesName.equals(othersLabel)) {
            return;
        }
        String value = valueBySeriesName.getOrDefault(seriesName, seriesName);

        JPopupMenu menu = new JPopupMenu();
        JMenuItem item = new JMenuItem(Labels.getLabel("gui.filter.popup.by") + " " + col + " = " + seriesName.trim());
        item.addActionListener(a -> ashFilter.setCondition(col, Collections.singleton(value)));
        menu.add(item);

        SwingUtilities.invokeLater(() -> menu.show(stackChartPanel.getStackedChart().getChartPanel(),
                event.getTrigger().getX(), event.getTrigger().getY()));
    }
}
