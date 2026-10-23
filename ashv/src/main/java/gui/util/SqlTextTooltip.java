package gui.util;

import config.Labels;
import core.sqltext.SqlTexts;
import gui.filter.FilterPopup;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;

/**
 * Tooltip with the beginning of the SQL text on SQL_ID cells, and "Copiar texto do SQL" in the right click menu
 */
public final class SqlTextTooltip {
    private static final int MAX_LINES = 15;
    private static final int MAX_LINE_LENGTH = 110;

    private SqlTextTooltip() {}

    /**
     * @param sqlIdAt (view row, view column) -> SQL_ID of the cell, null for other cells
     */
    public static void install(JTable table, BiFunction<Integer, Integer, String> sqlIdAt) {
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { update(table, sqlIdAt, e.getPoint()); }
            @Override public void mouseExited(MouseEvent e) { setTip(table, null); }
        };
        table.addMouseMotionListener(mouse);
        table.addMouseListener(mouse);

        FilterPopup.addMenuItems(table, (row, col) -> {
            String sqlId = sqlIdAt.apply(row, col);
            if (SqlTexts.get() == null || sqlId == null || sqlId.isEmpty()) {
                return Collections.emptyList();
            }
            JMenuItem copy = new JMenuItem(Labels.getLabel("gui.sqltext.copy") + " " + sqlId);
            copy.addActionListener(a -> copy(table, sqlId));
            List<JMenuItem> items = new ArrayList<>();
            items.add(copy);
            return items;
        });
    }

    // The table tooltip is set as a client property: setToolTipText(null) would unregister the table from the
    // ToolTipManager and remove the tooltips of the cell renderers (e.g. the activity bars)
    private static void setTip(JTable table, String text) {
        table.putClientProperty(JComponent.TOOL_TIP_TEXT_KEY, text);
    }

    private static void update(JTable table, BiFunction<Integer, Integer, String> sqlIdAt, Point point) {
        SqlTexts texts = SqlTexts.get();
        int row = table.rowAtPoint(point);
        int col = table.columnAtPoint(point);
        String sqlId = texts == null || row < 0 || col < 0 ? null : sqlIdAt.apply(row, col);
        if (sqlId == null || sqlId.isEmpty()) {
            setTip(table, null);
            return;
        }

        String text = texts.cached(sqlId);
        if (text != null) {
            setTip(table, html(sqlId, text));
        } else if (texts.isNotFound(sqlId)) {
            setTip(table, message(sqlId, Labels.getLabel("gui.sqltext.notfound")));
        } else if (texts.canFetch(sqlId)) {
            setTip(table, message(sqlId, Labels.getLabel("gui.sqltext.loading")));
            texts.fetch(sqlId, done -> refresh(table, sqlIdAt),
                    error -> {
                        setTip(table, message(sqlId, Labels.getLabel("gui.sqltext.error") + " " + error));
                        refresh(table, null);
                    });
        } else if (!texts.isLoading(sqlId)) {
            setTip(table, message(sqlId, Labels.getLabel("gui.sqltext.unavailable")));
        }
    }

    /** Show the new text when the mouse is still on the table */
    private static void refresh(JTable table, BiFunction<Integer, Integer, String> sqlIdAt) {
        PointerInfo pointer = MouseInfo.getPointerInfo();
        if (pointer == null || !table.isShowing()) {
            return;
        }
        Point point = pointer.getLocation();
        SwingUtilities.convertPointFromScreen(point, table);
        if (!table.contains(point)) {
            return;
        }
        if (sqlIdAt != null) {
            update(table, sqlIdAt, point);
        }
        ToolTipManager.sharedInstance().mouseMoved(new MouseEvent(table, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, point.x, point.y, 0, false));
    }

    private static void copy(JTable table, String sqlId) {
        SqlTexts texts = SqlTexts.get();
        String text = texts.cached(sqlId);
        if (text != null) {
            toClipboard(text);
        } else if (texts.canFetch(sqlId)) {
            texts.fetch(sqlId, done -> {
                if (done.isEmpty()) {
                    show(table, sqlId + ": " + Labels.getLabel("gui.sqltext.notfound"));
                } else {
                    toClipboard(done);
                }
            }, error -> show(table, sqlId + ": " + Labels.getLabel("gui.sqltext.error") + " " + error));
        } else {
            show(table, sqlId + ": " + Labels.getLabel(texts.isNotFound(sqlId)
                    ? "gui.sqltext.notfound" : "gui.sqltext.unavailable"));
        }
    }

    private static void toClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private static void show(JTable table, String message) {
        JOptionPane.showMessageDialog(table, message, Labels.getLabel("gui.sqltext.title"), JOptionPane.INFORMATION_MESSAGE);
    }

    private static String message(String sqlId, String text) {
        return "<html><b>" + escape(sqlId) + "</b><br><i>" + escape(text) + "</i></html>";
    }

    /** First lines of the text, long lines wrapped */
    static String html(String sqlId, String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.replace("\t", "    ").replace("\r", "").split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            while (line.length() > MAX_LINE_LENGTH) {
                int cut = line.lastIndexOf(' ', MAX_LINE_LENGTH);
                cut = cut < MAX_LINE_LENGTH / 2 ? MAX_LINE_LENGTH : cut;
                lines.add(line.substring(0, cut));
                line = "  " + line.substring(cut).trim();
            }
            lines.add(line);
            if (lines.size() > MAX_LINES) {
                break;
            }
        }
        boolean truncated = lines.size() > MAX_LINES;
        if (truncated) {
            lines = lines.subList(0, MAX_LINES);
        }

        StringBuilder sb = new StringBuilder("<html><b>").append(escape(sqlId)).append("</b><pre>");
        for (String line : lines) {
            sb.append(escape(line)).append('\n');
        }
        if (truncated) {
            sb.append("...\n");
        }
        return sb.append("</pre></html>").toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
