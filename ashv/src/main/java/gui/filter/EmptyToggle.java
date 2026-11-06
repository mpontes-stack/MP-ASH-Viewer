package gui.filter;

import config.Labels;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.prefs.Preferences;
import javax.swing.JCheckBox;

/**
 * "Hide (empty)" next to "Group by" (Top Activity, History) and "Compare by" (Compare): the "(empty)" value of the
 * grouped column (sessions without MODULE, ACTION...) is left out of the Group by charts and the Compare by table.
 * Not a filter: the other tables keep those sessions. All the boxes show the same choice, kept for the next
 * connections.
 */
public class EmptyToggle extends JCheckBox {
    private static final String PREFERENCES_NODE = "MP-ASH-Viewer";
    private static final String HIDE_EMPTY = "hideEmpty";

    private static volatile boolean hide = saved();
    private static final List<EmptyToggle> toggles = new CopyOnWriteArrayList<>();

    private final Runnable onChange;

    /** @param onChange redraws what this box is next to (on the EDT) */
    public EmptyToggle(Runnable onChange) {
        super(Labels.getLabel("gui.empty.hide"), hide);
        this.onChange = onChange;
        setToolTipText(Labels.getLabel("gui.empty.hide.tooltip"));
        setOpaque(false);
        addActionListener(e -> setHide(isSelected()));
        toggles.add(this);
    }

    public static boolean isHide() {
        return hide;
    }

    /** Same choice in every box, each one redraws its chart or table */
    private static void setHide(boolean value) {
        if (hide == value) {
            return;
        }
        hide = value;
        save(value);
        for (EmptyToggle t : toggles) {
            if (!t.isDisplayable()) { // removed from the window (connection closed)
                toggles.remove(t);
                continue;
            }
            t.setSelected(value);
            t.onChange.run();
        }
    }

    private static boolean saved() {
        try {
            return Preferences.userRoot().node(PREFERENCES_NODE).getBoolean(HIDE_EMPTY, false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void save(boolean value) {
        try {
            Preferences.userRoot().node(PREFERENCES_NODE).putBoolean(HIDE_EMPTY, value);
        } catch (Exception ignore) {
            // the choice is only not remembered
        }
    }
}
