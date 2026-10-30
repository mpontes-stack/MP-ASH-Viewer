package gui.filter;

import config.Labels;
import config.profile.SqlColProfile;
import core.filter.AshFilter;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;

/**
 * "Hide background" next to the Top Activity, History and Compare charts: all of them follow the same
 * {@link AshFilter} flag, so checking one checks the others. The choice is kept for the next connections.
 */
public class BackgroundToggle extends JCheckBox {
    private static final String PREFERENCES_NODE = "MP-ASH-Viewer";
    private static final String HIDE_BACKGROUND = "hideBackground";

    public BackgroundToggle(AshFilter ashFilter) {
        super(Labels.getLabel("gui.background.hide"), ashFilter.isHideBackground());
        setToolTipText(Labels.getLabel("gui.background.hide.tooltip"));
        setOpaque(false);
        addActionListener(e -> {
            ashFilter.setHideBackground(isSelected());
            save(isSelected());
        });
        ashFilter.addListener(() -> SwingUtilities.invokeLater(() -> setSelected(ashFilter.isHideBackground())));
    }

    /** Only connections that collect SESSION_TYPE (Oracle) can tell the background processes apart */
    public static boolean isAvailable(List<SqlColProfile> cols) {
        return cols != null && cols.stream().anyMatch(c -> AshFilter.SESSION_TYPE.equalsIgnoreCase(c.getColName()));
    }

    /** The choice saved by the last connection (false when never chosen) */
    public static boolean saved() {
        try {
            return Preferences.userRoot().node(PREFERENCES_NODE).getBoolean(HIDE_BACKGROUND, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void save(boolean hide) {
        try {
            Preferences.userRoot().node(PREFERENCES_NODE).putBoolean(HIDE_BACKGROUND, hide);
        } catch (Exception ignore) {
            // the choice is only not remembered
        }
    }
}
