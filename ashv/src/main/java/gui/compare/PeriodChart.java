package gui.compare;

import config.Labels;
import core.filter.AshFilter;
import core.filter.FilteredAshLoader;
import core.manager.ColorManager;
import core.manager.ConstantManager;
import gui.chart.CategoryTableXYDatasetRDA;
import gui.chart.panel.StackChartPanel;
import gui.util.Themes;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.Layer;
import profile.IProfile;

/**
 * Chart by wait class (filtered samples) of one period of the comparison, with some time before and after:
 * the period is the shaded interval, a mouse selection gives a new period
 */
@Slf4j
class PeriodChart extends JPanel {
    /** Time shown before and after the period: a quarter of it, at least 2 minutes */
    private static final long MIN_MARGIN_MS = 120_000;

    private final FilteredAshLoader filteredAshLoader;
    private final ColorManager colorManager;
    private final Consumer<long[]> onSelection;
    private final Runnable onLoaded;
    private final AtomicInteger generation = new AtomicInteger();

    private XYPlot plot;
    /** Highest stacked value (AAS) of the chart */
    private double max;

    /**
     * @param onSelection range selected with the mouse
     * @param onLoaded    chart shown (to share the vertical scale)
     */
    PeriodChart(FilteredAshLoader filteredAshLoader, ColorManager colorManager, Consumer<long[]> onSelection,
                Runnable onLoaded) {
        super(new BorderLayout());
        this.filteredAshLoader = filteredAshLoader;
        this.colorManager = colorManager;
        this.onSelection = onSelection;
        this.onLoaded = onLoaded;
        setPreferredSize(new Dimension(600, 170));
        setMinimumSize(new Dimension(100, 90));
    }

    double getMax() {
        return plot == null ? 0 : max;
    }

    /** Same vertical scale on both charts */
    void setUpperBound(double upper) {
        if (plot != null && upper > 0) {
            plot.getRangeAxis().setAutoRange(false);
            plot.getRangeAxis().setRange(0, upper * 1.05);
        }
    }

    void clear(String message) {
        generation.incrementAndGet();
        plot = null;
        message(message);
    }

    /** Read the samples in the background and show the chart */
    void load(long[] range, AshFilter.Matcher matcher, IProfile iProfile) {
        int gen = generation.incrementAndGet();
        if (plot == null) {
            message(Labels.getLabel("gui.compare.loading"));
        }
        long margin = Math.max(MIN_MARGIN_MS, (range[1] - range[0]) / 4);
        long begin = range[0] - margin;
        long end = range[1] + margin;
        long step = Math.max(1, Math.round((double) (end - begin) / ConstantManager.MAX_POINT_PER_GRAPH));

        Thread t = new Thread(() -> {
            Map<Long, Map<String, Map<String, Integer>>> buckets;
            try {
                buckets = filteredAshLoader.countByBucket(matcher, iProfile, begin, end, step);
            } catch (Exception e) {
                log.error("Compare chart", e);
                SwingUtilities.invokeLater(() -> {
                    if (generation.get() == gen) message(Labels.getLabel("gui.compare.error") + " " + e);
                });
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (generation.get() == gen) show(range, begin, end, step, buckets, iProfile);
            });
        }, "compare-chart");
        t.setDaemon(true);
        t.start();
    }

    private void show(long[] range, long begin, long end, long step,
                      Map<Long, Map<String, Map<String, Integer>>> buckets, IProfile iProfile) {
        CategoryTableXYDatasetRDA dataset = new CategoryTableXYDatasetRDA();
        StackChartPanel chart = new StackChartPanel(null, colorManager);
        chart.setXyDatasetRDA(dataset);
        chart.setXAxisLabel(" ");
        chart.setLegendFontSize(8);
        chart.initialize();

        LinkedHashSet<String> waitClasses = iProfile.getUniqueTreeEventListByWaitClass();
        waitClasses.forEach(w -> chart.getStackedChart().setSeriesPaintDynamicDetail(w));
        double seconds = step / 1000d;
        double highest = 0;
        for (long d = begin; d < end; d += step) {
            Map<String, Map<String, Integer>> byClass = buckets.getOrDefault(d, Collections.emptyMap());
            double stacked = 0;
            for (String w : waitClasses) {
                int sum = byClass.getOrDefault(w, Collections.emptyMap()).values().stream().mapToInt(Integer::intValue).sum();
                dataset.setSeriesValueNoNotify(d, sum / seconds, w);
                stacked += sum / seconds;
            }
            highest = Math.max(highest, stacked);
        }
        dataset.notifyChanged();

        XYPlot xyPlot = (XYPlot) chart.getStackedChart().getChartPanel().getChart().getPlot();
        xyPlot.getDomainAxis().setRange(begin, end);
        boolean dark = Themes.isDark();
        IntervalMarker marker = new IntervalMarker(range[0], range[1],
                dark ? new Color(255, 255, 255, 40) : new Color(70, 130, 220, 45),
                new BasicStroke(1.5f), dark ? new Color(230, 230, 230) : new Color(40, 90, 180),
                new BasicStroke(1.5f), 1f);
        xyPlot.addDomainMarker(marker, Layer.FOREGROUND);
        chart.addChartListenerReleaseMouse(g -> {
            long from = (long) g.getBeginTime();
            long to = (long) g.getEndTime();
            if (to > from) SwingUtilities.invokeLater(() -> onSelection.accept(new long[]{from, to}));
        });

        plot = xyPlot;
        max = highest;
        removeAll();
        add(chart, BorderLayout.CENTER);
        revalidate();
        repaint();
        onLoaded.run();
    }

    private void message(String text) {
        removeAll();
        add(new JLabel(text, SwingConstants.CENTER), BorderLayout.CENTER);
        revalidate();
        repaint();
    }
}
