package gui.util;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Window;
import java.util.prefs.Preferences;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.metal.MetalLookAndFeel;
import lombok.extern.slf4j.Slf4j;
import org.rtv.Options;

/**
 * Themes light (default) | dark | classic: the last choice of the connection dialog,
 * otherwise -Dashv.theme
 */
@Slf4j
public final class Themes {
    public static final String LIGHT = "light";
    public static final String DARK = "dark";
    public static final String CLASSIC = "classic";

    private static final String THEME = "theme";
    // own node: the "ASH-Viewer" node keeps the connection secrets
    private static final String PREFERENCES_NODE = "MP-ASH-Viewer";

    // Configuration, Administrative (and Postgres Timeout) are dark brown / olive: almost invisible on the dark background
    private static final Color CONFIGURATION = new Color(84, 56, 28);
    private static final Color ADMINISTRATIVE = new Color(84, 84, 29);
    private static final Color CONFIGURATION_DARK = new Color(145, 95, 50);
    private static final Color ADMINISTRATIVE_DARK = new Color(150, 150, 40);

    private static String current = LIGHT;

    private Themes() {}

    public static String current() {
        return current;
    }

    /** Theme of the application start */
    public static String startup() {
        String saved = null;
        try {
            saved = Preferences.userRoot().node(PREFERENCES_NODE).get(THEME, null);
        } catch (Exception e) {
            log.info("Theme preference: " + e.getMessage());
        }
        return normalize(saved != null ? saved : System.getProperty("ashv.theme", LIGHT));
    }

    /** Set the look and feel (before the windows are created) */
    public static void apply(String theme) {
        theme = normalize(theme);
        try {
            switch (theme) {
                case CLASSIC:
                    UIManager.setLookAndFeel(new MetalLookAndFeel());
                    break;
                case DARK:
                    FlatDarkLaf.setup();
                    break;
                default:
                    FlatLightLaf.setup();
            }

            UIManager.put("Component.arc", 6);
            UIManager.put("Button.arc", 6);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Tree.showDefaultIcons", true);
            current = theme;
        } catch (Exception e) {
            log.warn("Unable to set look and feel " + theme + ": " + e.getMessage());
        }
        waitClassColors(DARK.equals(current));
    }

    /** Change the theme of the open windows and remember it for the next start */
    public static void switchTo(String theme) {
        save(theme);
        apply(theme);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
        }
    }

    public static void save(String theme) {
        try {
            Preferences.userRoot().node(PREFERENCES_NODE).put(THEME, normalize(theme));
        } catch (Exception e) {
            log.info("Theme preference: " + e.getMessage());
        }
    }

    public static boolean isDark() {
        try {
            return FlatLaf.isLafDark();
        } catch (Throwable e) {
            return false;
        }
    }

    /** Foreground for light / dark theme, changed with the theme */
    public static void foreground(JComponent component, Color light, Color dark) {
        component.setForeground(isDark() ? dark : light);
        component.addPropertyChangeListener("UI", e -> component.setForeground(isDark() ? dark : light));
    }

    private static String normalize(String theme) {
        String t = theme == null ? LIGHT : theme.trim().toLowerCase();
        return DARK.equals(t) || CLASSIC.equals(t) ? t : LIGHT;
    }

    private static void waitClassColors(boolean dark) {
        Options.getInstance();
        Color configuration = dark ? CONFIGURATION_DARK : CONFIGURATION;
        Color administrative = dark ? ADMINISTRATIVE_DARK : ADMINISTRATIVE;
        Options.getOracleMainColor().put(Options.LBL_CONFIGURATION, configuration);
        Options.getOracleMainColor().put(Options.LBL_ADMINISTRATIVE, administrative);
        Options.getPgMainColor().put(Options.LBL_PG_TIMEOUT, configuration);
        Options.setColor(Options.LBL_CONFIGURATION, configuration);
        Options.setColor(Options.LBL_ADMINISTRATIVE, administrative);
        Options.setColor(Options.LBL_PG_TIMEOUT, configuration);
    }
}
