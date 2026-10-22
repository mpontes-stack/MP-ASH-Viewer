package gui.filter;

import config.Labels;
import config.profile.SqlColProfile;
import core.filter.AshFilter;
import core.filter.FilteredAshLoader;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import store.StoreManager;

/**
 * Bar above the main chart with the active filters, "+ Filter" and "Clear" buttons
 */
public class FilterBar extends JPanel {
    // Columns shown first in the column list (similar to Oracle EM dimensions)
    private static final List<String> PREFERRED_COLUMNS = Arrays.asList(
            "WAIT_CLASS", "EVENT", "SQL_ID", "SESSION_ID", "USERNAME", "PROGRAM", "MODULE", "ACTION",
            "MACHINE", "OSUSER", "SERVICE_NAME", "CLIENT_IDENTIFIER", "CLIENT_INFO", "COMMAND",
            "SESSION_TYPE", "SESSION_STATE", "BLOCKING_SESSION", "BLOCKING_SESSION_STATUS",
            "FINAL_BLOCKING_SESSION", "PLSQL_ENTRY_OBJECT_ID", "PREV_SQL_ID", "SQL_HASH_VALUE",
            "CURRENT_OBJ#", "USER_ID");

    private static final int MAX_CHIP_TEXT = 60;

    private final AshFilter ashFilter;
    private final FilteredAshLoader filteredAshLoader;
    private final StoreManager storeManager;
    private final Supplier<long[]> rangeSupplier;

    private final JLabel titleLbl = new JLabel(Labels.getLabel("gui.filter.title"));
    private final JLabel noFilterLbl = new JLabel(Labels.getLabel("gui.filter.none"));
    private final JButton addButton = new JButton(Labels.getLabel("gui.filter.add"));
    private final JButton clearButton = new JButton(Labels.getLabel("gui.filter.clear"));

    public FilterBar(AshFilter ashFilter, FilteredAshLoader filteredAshLoader,
                     StoreManager storeManager, Supplier<long[]> rangeSupplier) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 3));
        this.ashFilter = ashFilter;
        this.filteredAshLoader = filteredAshLoader;
        this.storeManager = storeManager;
        this.rangeSupplier = rangeSupplier;

        noFilterLbl.setEnabled(false);
        addButton.setToolTipText(Labels.getLabel("gui.filter.add.tooltip"));
        addButton.addActionListener(e -> openAddDialog());
        clearButton.addActionListener(e -> ashFilter.clear());

        ashFilter.addListener(() -> SwingUtilities.invokeLater(this::rebuild));
        rebuild();
    }

    private void rebuild() {
        removeAll();
        add(titleLbl);

        Map<String, Set<String>> conditions = ashFilter.getConditions();
        if (conditions.isEmpty()) {
            add(noFilterLbl);
        }

        conditions.forEach((colName, values) -> {
            String valuesText = values.stream().map(FilterBar::displayValue).collect(Collectors.joining(", "));
            String text = colName + " = " + valuesText;
            if (text.length() > MAX_CHIP_TEXT) {
                text = text.substring(0, MAX_CHIP_TEXT) + "...";
            }

            JButton chip = new JButton(text + "  ×");
            chip.putClientProperty("JButton.buttonType", "roundRect");
            chip.setToolTipText("<html>" + colName + " = " + escape(valuesText) + "<br>"
                    + Labels.getLabel("gui.filter.remove.tooltip") + "</html>");
            chip.addActionListener(e -> ashFilter.removeCondition(colName));
            add(chip);
        });

        add(addButton);
        clearButton.setVisible(!conditions.isEmpty());
        add(clearButton);

        revalidate();
        repaint();
    }

    private void openAddDialog() {
        List<String> columns = getFilterColumns();
        if (columns.isEmpty()) {
            return;
        }
        new AddFilterDialog(SwingUtilities.getWindowAncestor(this), ashFilter, filteredAshLoader,
                columns, rangeSupplier).setVisible(true);
    }

    private List<String> getFilterColumns() {
        List<SqlColProfile> cols = storeManager.getConfigurationManager().getCurrentConfiguration().getSqlColProfileList();
        if (cols == null) {
            return new ArrayList<>();
        }

        List<String> available = cols.stream().map(SqlColProfile::getColName).collect(Collectors.toList());

        List<String> out = PREFERRED_COLUMNS.stream().filter(available::contains).collect(Collectors.toList());
        available.stream()
                .filter(c -> !out.contains(c) && !c.equalsIgnoreCase("SAMPLE_TIME"))
                .sorted()
                .forEach(out::add);
        return out;
    }

    static String displayValue(String value) {
        return value == null || value.isEmpty() ? Labels.getLabel("gui.filter.empty") : value;
    }

    static String storedValue(String display) {
        return Labels.getLabel("gui.filter.empty").equals(display) ? Labels.getLabel("local.null") : display;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
