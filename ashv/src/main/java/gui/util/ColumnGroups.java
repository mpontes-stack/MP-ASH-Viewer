package gui.util;

import config.Labels;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.UIManager;

/**
 * Columns of "Group by" and "Compare by" in groups (Wait, SQL, PL/SQL, Session...): the group titles are shown in
 * the list but can not be chosen, the columns are indented under them, some with a short description in gray
 */
public final class ColumnGroups {
    /** Title prefix of the group items in the model (never a column name) */
    private static final String HEADER = "\u0001";
    private static final int MAX_ROWS = 30;

    /** Label key, then the columns in the order shown (Oracle and PostgreSQL names; compared ignoring case) */
    private static final String[][] GROUPS = {
            {"gui.colgroup.wait", "WAIT_CLASS", "EVENT", "SESSION_STATE", "CURRENT_OBJ#", "CURRENT_FILE#",
                    "CURRENT_BLOCK#", "P1", "P2", "P3", "WAIT_TIME", "TIME_WAITED", "EVENT#", "SEQ#", "WAIT_CLASS_ID",
                    "WAIT_CLASS#"},
            {"gui.colgroup.sql", "SQL_ID", "TOP_LEVEL_SQL_ID", "PREV_SQL_ID", "SQL_PLAN_HASH_VALUE", "SQL_HASH_VALUE",
                    "SQL_CHILD_NUMBER", "SQL_EXEC_ID", "SQL_EXEC_START", "COMMAND", "SQL_OPCODE", "SQL_OPNAME",
                    "QUERY_START", "DURATION"},
            {"gui.colgroup.plsql", "PLSQL_ENTRY", core.plsql.PlsqlSource.COLUMN, "PLSQL_ENTRY_OBJECT_ID",
                    "PLSQL_ENTRY_SUBPROGRAM_ID"},
            {"gui.colgroup.session", "SESSION", "SESSION_ID", "SESSION_SERIAL#", "SESSION_SERIAL", "USERNAME", "USENAME",
                    "USER_ID", "SESSION_TYPE", "SERVICE_NAME", "DATNAME", "DATID"},
            {"gui.colgroup.application", "MODULE", "ACTION", "PROGRAM", "CLIENT_IDENTIFIER", "CLIENT_INFO", "MACHINE",
                    "OSUSER"},
            {"gui.colgroup.locks", "BLOCKING_SESSION", "BLOCKING_SESSION_STATUS", "FINAL_BLOCKING_SESSION",
                    "BLOCKING_INSTANCE", "FINAL_BLOCKING_INSTANCE"}};

    private static final Map<String, String> DESCRIPTIONS = new HashMap<>();

    static {
        DESCRIPTIONS.put("PLSQL_ENTRY", "gui.colgroup.desc.plsqlentry");
        DESCRIPTIONS.put(core.plsql.PlsqlSource.COLUMN, "gui.colgroup.desc.plsqlsource");
    }

    private ColumnGroups() {
    }

    /**
     * Put the items in the box: {@code first} (not grouped, e.g. "Wait class"), then the columns by group, in the
     * order of the groups; columns of no group go in "Others". The first item is selected.
     *
     * @param display text of an item (column name as is, or a label)
     */
    public static void setItems(JComboBox<String> box, List<String> first, Collection<String> columns,
                                Function<String, String> display) {
        List<String> items = new ArrayList<>(first);
        Map<String, String> left = new java.util.LinkedHashMap<>(); // upper case -> name as given
        columns.forEach(c -> left.putIfAbsent(c.toUpperCase(), c));
        for (String[] group : GROUPS) {
            List<String> in = new ArrayList<>();
            for (String col : Arrays.asList(group).subList(1, group.length)) {
                String given = left.remove(col);
                if (given != null) {
                    in.add(given);
                }
            }
            addGroup(items, group[0], in);
        }
        addGroup(items, "gui.colgroup.others", new ArrayList<>(left.values()));

        Model model = new Model(items);
        box.setModel(model);
        box.setRenderer(new Renderer(display));
        box.setMaximumRowCount(Math.min(items.size(), MAX_ROWS));
    }

    private static void addGroup(List<String> items, String labelKey, List<String> columns) {
        if (!columns.isEmpty()) {
            items.add(HEADER + labelKey);
            items.addAll(columns);
        }
    }

    private static boolean isHeader(Object item) {
        return item instanceof String && ((String) item).startsWith(HEADER);
    }

    /** A group title is never selected: the column next to it in the direction of the move is */
    private static final class Model extends DefaultComboBoxModel<String> {
        Model(List<String> items) {
            super(items.toArray(new String[0]));
            for (String item : items) {
                if (!isHeader(item)) {
                    super.setSelectedItem(item);
                    break;
                }
            }
        }

        @Override
        public void setSelectedItem(Object item) {
            if (isHeader(item)) {
                int header = getIndexOf(item);
                // arrow up from the first column of the group: the column before the title
                boolean up = getIndexOf(getSelectedItem()) == header + 1;
                item = column(header, up ? -1 : 1);
                if (item == null) {
                    item = column(header, up ? 1 : -1);
                }
                if (item == null) {
                    return;
                }
            }
            super.setSelectedItem(item);
        }

        private String column(int from, int step) {
            for (int i = from + step; i >= 0 && i < getSize(); i += step) {
                if (!isHeader(getElementAt(i))) {
                    return getElementAt(i);
                }
            }
            return null;
        }
    }

    private static final class Renderer extends DefaultListCellRenderer {
        private final Function<String, String> display;

        Renderer(Function<String, String> display) {
            this.display = display;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
                                                      boolean cellHasFocus) {
            String item = value == null ? "" : String.valueOf(value);
            if (isHeader(item)) {
                super.getListCellRendererComponent(list, Labels.getLabel(item.substring(HEADER.length())), index,
                        false, false);
                setFont(getFont().deriveFont(Font.BOLD));
                setForeground(Ui.accent(this));
                setBorder(BorderFactory.createEmptyBorder(4, 4, 1, 4));
                return this;
            }
            String text = display.apply(item);
            String description = DESCRIPTIONS.get(item);
            if (index >= 0 && description != null) { // in the open list only
                text = "<html>" + escape(text) + "&nbsp;&nbsp;<font color='" + hex(gray()) + "'>"
                        + escape(Labels.getLabel(description)) + "</font></html>";
            }
            super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
            if (index >= 0 && isGrouped(list, index)) {
                setBorder(BorderFactory.createEmptyBorder(1, 16, 1, 4));
            }
            return this;
        }

        /** Under a group title (the first items, e.g. "Wait class", are not) */
        private static boolean isGrouped(JList<?> list, int index) {
            for (int i = index - 1; i >= 0; i--) {
                if (isHeader(list.getModel().getElementAt(i))) {
                    return true;
                }
            }
            return false;
        }

        private static Color gray() {
            Color c = UIManager.getColor("Label.disabledForeground");
            return c == null ? Color.GRAY : c;
        }

        private static String hex(Color c) {
            return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
