package gui.filter;

import config.Labels;
import core.filter.AshFilter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTable;

/**
 * Right click menu on a table cell: "Filter by COLUMN = value"
 */
public final class FilterPopup {

    private FilterPopup() {}

    /**
     * Single filter condition (column name, value)
     */
    public static Map.Entry<String, String> condition(String colName, String value) {
        return new AbstractMap.SimpleImmutableEntry<>(colName, value);
    }

    /**
     * @param conditionsForCell (view row, view column) -> list of menu entries, each entry a list of
     *                          conditions applied together (e.g. SESSION_ID and SESSION_SERIAL#)
     */
    public static void install(JTable table, AshFilter ashFilter,
                               BiFunction<Integer, Integer, List<List<Map.Entry<String, String>>>> conditionsForCell) {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { showPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { showPopup(e); }

            private void showPopup(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                int col = table.columnAtPoint(e.getPoint());
                if (row < 0 || col < 0) {
                    return;
                }
                table.changeSelection(row, col, false, false);

                List<List<Map.Entry<String, String>>> entries = conditionsForCell.apply(row, col);
                if (entries == null || entries.isEmpty()) {
                    return;
                }

                JPopupMenu menu = new JPopupMenu();
                Map<String, Set<String>> current = ashFilter.getConditions();

                for (List<Map.Entry<String, String>> conditions : entries) {
                    JMenuItem item = new JMenuItem(Labels.getLabel("gui.filter.popup.by") + " " + describe(conditions));
                    item.addActionListener(a -> conditions.forEach(c ->
                            ashFilter.setCondition(c.getKey(), Collections.singleton(c.getValue()))));
                    menu.add(item);

                    // Add value to an existing condition of the same column (OR)
                    if (conditions.size() == 1) {
                        Map.Entry<String, String> c = conditions.get(0);
                        Set<String> values = current.get(c.getKey());
                        if (values != null && !values.contains(c.getValue())) {
                            JMenuItem orItem = new JMenuItem(Labels.getLabel("gui.filter.popup.or") + " " + describe(conditions));
                            orItem.addActionListener(a -> {
                                Set<String> newValues = new LinkedHashSet<>(values);
                                newValues.add(c.getValue());
                                ashFilter.setCondition(c.getKey(), newValues);
                            });
                            menu.add(orItem);
                        }
                    }
                }

                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private static String describe(List<Map.Entry<String, String>> conditions) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> c : conditions) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            String value = FilterBar.displayValue(c.getValue());
            if (value.length() > 50) {
                value = value.substring(0, 50) + "...";
            }
            sb.append(c.getKey()).append(" = ").append(value);
        }
        return sb.toString();
    }
}
