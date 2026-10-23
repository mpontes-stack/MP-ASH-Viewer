package gui.chart.panel;

import com.formdev.flatlaf.FlatLaf;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import javax.swing.UIManager;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.Axis;
import org.jfree.chart.block.BlockBorder;
import org.jfree.chart.block.BlockContainer;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.chart.ui.RectangleEdge;

/**
 * Look of the stacked charts, selected with -Dashv.chartstyle=classic|clean|soft
 * (colors follow the light / dark theme)
 */
public enum ChartStyle {
    CLASSIC, CLEAN, SOFT;

    public static ChartStyle current() {
        try {
            return valueOf(System.getProperty("ashv.chartstyle", "clean").toUpperCase());
        } catch (IllegalArgumentException e) {
            return CLEAN;
        }
    }

    private static boolean isDark() {
        try {
            return FlatLaf.isLafDark();
        } catch (Throwable e) {
            return false;
        }
    }

    private static Color ui(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color != null ? color : fallback;
    }

    private static Color background() { return isDark() ? ui("Panel.background", new Color(60, 63, 65)) : Color.WHITE; }
    private static Color foreground() { return ui("Label.foreground", Color.DARK_GRAY); }
    private static Color secondary() { return isDark() ? new Color(160, 163, 166) : new Color(110, 110, 110); }

    private static Font font(int delta, int style) {
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(style, base.getSize2D() + delta);
    }

    /**
     * Apply the style to chart, plot and legend (legend wrapper = border container around the items)
     */
    public void apply(JFreeChart chart, XYPlot plot, LegendTitle legend, BlockContainer legendWrapper, int legendFontSize) {
        if (this == CLASSIC) {
            return;
        }
        boolean dark = isDark();
        Color bg = background();

        chart.setBackgroundPaint(bg);
        // no antialias on areas: it shows thin seams between the polygons of each point
        chart.setAntiAlias(Boolean.getBoolean("ashv.chartaa"));
        chart.setTextAntiAlias(true);

        if (this == CLEAN) {
            plot.setBackgroundPaint(bg);
            plot.setRangeGridlinePaint(dark ? new Color(255, 255, 255, 40) : new Color(0, 0, 0, 30));
        } else { // SOFT
            plot.setBackgroundPaint(dark ? new Color(255, 255, 255, 12) : new Color(244, 245, 248));
            plot.setRangeGridlinePaint(dark ? new Color(255, 255, 255, 30) : Color.WHITE);
        }
        plot.setRangeGridlineStroke(new BasicStroke(this == SOFT ? 1.2f : 0.8f));
        plot.setDomainGridlinesVisible(false);
        plot.setOutlineVisible(false);
        plot.setDomainCrosshairVisible(false);
        plot.setRangeCrosshairVisible(false);
        plot.setForegroundAlpha(0.92f);

        styleAxis(plot.getDomainAxis());
        styleAxis(plot.getRangeAxis());

        styleTitle(chart);

        if (legend != null) {
            legend.setBackgroundPaint(bg);
            legend.setItemPaint(foreground());
            Font legendFont = font(-1, Font.PLAIN);
            legend.setItemFont(legendFont.deriveFont(Math.min(legendFont.getSize2D(), (float) legendFontSize)));
            legend.setFrame(BlockBorder.NONE);
            if (legendWrapper != null) {
                legendWrapper.setFrame(BlockBorder.NONE);
            }
            if (this == SOFT) {
                legend.setPosition(RectangleEdge.BOTTOM);
            }
        }
    }

    /**
     * Legend color marker: rounded square (null keeps the default area shape)
     */
    public java.awt.Shape legendShape() {
        return this == CLASSIC ? null : new java.awt.geom.RoundRectangle2D.Double(-5, -5, 10, 10, 4, 4);
    }

    public void styleAxis(Axis axis) {
        if (this == CLASSIC || axis == null) {
            return;
        }
        axis.setAxisLineVisible(false);
        axis.setTickMarksVisible(false);
        axis.setTickLabelFont(font(-1, Font.PLAIN));
        axis.setTickLabelPaint(secondary());
        axis.setLabelFont(font(-1, Font.PLAIN));
        axis.setLabelPaint(secondary());
    }

    public void styleTitle(JFreeChart chart) {
        if (this == CLASSIC) {
            return;
        }
        TextTitle title = chart.getTitle();
        if (title != null) {
            title.setFont(font(1, Font.BOLD));
            title.setPaint(foreground());
            title.setBackgroundPaint(null);
        }
    }
}
