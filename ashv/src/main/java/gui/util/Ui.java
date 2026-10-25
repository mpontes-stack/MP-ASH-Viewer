package gui.util;

import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.UIManager;
import javax.swing.border.EtchedBorder;

/**
 * Common look of the screens: blue section titles (as in the "Agora" tabs) and highlighted bars (filters)
 */
public final class Ui {
    private Ui() {}

    /** Accent color of the look and feel (blue), the label color when there is none */
    public static Color accent(JComponent fallback) {
        Color c = UIManager.getColor("Component.accentColor");
        return c != null ? c : fallback.getForeground();
    }

    /** Bold title in the accent color; follows a theme change */
    public static JLabel title(String text) {
        JLabel title = new JLabel(text);
        style(title);
        title.addPropertyChangeListener("UI", e -> style(title));
        return title;
    }

    /** Make an existing label a section title */
    public static JLabel asTitle(JLabel label) {
        style(label);
        label.addPropertyChangeListener("UI", e -> style(label));
        return label;
    }

    private static void style(JLabel label) {
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setForeground(accent(label));
    }

    /**
     * A bar slightly apart from the background, with the etched border of the History period buttons; follows a theme
     * change
     */
    public static void highlight(JComponent bar) {
        bar.setOpaque(true);
        applyHighlight(bar);
        bar.addPropertyChangeListener("UI", e -> applyHighlight(bar));
    }

    private static void applyHighlight(JComponent bar) {
        Color bg = UIManager.getColor("Panel.background");
        if (bg != null) {
            boolean dark = (bg.getRed() + bg.getGreen() + bg.getBlue()) / 3 < 128;
            int d = dark ? 10 : -8;
            bar.setBackground(new Color(clamp(bg.getRed() + d), clamp(bg.getGreen() + d), clamp(bg.getBlue() + d + (dark ? 4 : 0))));
        }
        bar.setBorder(BorderFactory.createCompoundBorder(new EtchedBorder(), BorderFactory.createEmptyBorder(1, 2, 1, 2)));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
