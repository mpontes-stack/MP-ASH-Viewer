package gui.filter;

import config.Labels;
import config.profile.SqlColProfile;
import core.filter.AshFilter;
import core.filter.FilterPresets;
import core.filter.FilteredAshLoader;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
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
    private final FilterPresets filterPresets;
    private final StoreManager storeManager;
    private final Supplier<long[]> rangeSupplier;

    private final JLabel titleLbl = new JLabel(Labels.getLabel("gui.filter.title"));
    private final JLabel noFilterLbl = new JLabel(Labels.getLabel("gui.filter.none"));
    private final JButton addButton = new JButton(Labels.getLabel("gui.filter.add"));
    private final JButton clearButton = new JButton(Labels.getLabel("gui.filter.clear"));
    private final JButton saveButton = new JButton(Labels.getLabel("gui.filter.preset.save"));
    private final JButton presetsButton = new JButton(Labels.getLabel("gui.filter.preset.list") + " \u25BE");

    public FilterBar(AshFilter ashFilter, FilteredAshLoader filteredAshLoader, FilterPresets filterPresets,
                     StoreManager storeManager, Supplier<long[]> rangeSupplier) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 3));
        this.ashFilter = ashFilter;
        this.filteredAshLoader = filteredAshLoader;
        this.filterPresets = filterPresets;
        this.storeManager = storeManager;
        this.rangeSupplier = rangeSupplier;

        noFilterLbl.setEnabled(false);
        addButton.setToolTipText(Labels.getLabel("gui.filter.add.tooltip"));
        addButton.addActionListener(e -> openAddDialog());
        clearButton.addActionListener(e -> ashFilter.clear());
        saveButton.setToolTipText(Labels.getLabel("gui.filter.preset.save.tooltip"));
        saveButton.addActionListener(e -> savePreset());
        presetsButton.setToolTipText(Labels.getLabel("gui.filter.preset.list.tooltip"));
        presetsButton.addActionListener(e -> showPresets());

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

        conditions.forEach((key, values) -> {
            String fullText = describeCondition(key, values);
            String text = fullText.length() > MAX_CHIP_TEXT ? fullText.substring(0, MAX_CHIP_TEXT) + "..." : fullText;

            JButton chip = new JButton(text + "  \u00D7");
            chip.putClientProperty("JButton.buttonType", "roundRect");
            if (AshFilter.isExclusion(key)) {
                chip.putClientProperty("JComponent.outline", "error"); // exclusions in red
            }
            chip.setToolTipText("<html>" + escape(fullText) + "<br>"
                    + Labels.getLabel("gui.filter.remove.tooltip") + "</html>");
            chip.addActionListener(e -> ashFilter.removeCondition(key));
            add(chip);
        });

        add(addButton);
        clearButton.setVisible(!conditions.isEmpty());
        add(clearButton);
        saveButton.setVisible(!conditions.isEmpty());
        add(saveButton);
        add(presetsButton);

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

    private void savePreset() {
        Map<String, Set<String>> conditions = ashFilter.getConditions();
        if (conditions.isEmpty()) {
            return;
        }
        String name = JOptionPane.showInputDialog(this, Labels.getLabel("gui.filter.preset.name"),
                Labels.getLabel("gui.filter.preset.save"), JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        name = name.trim();
        if (filterPresets.contains(name)
                && JOptionPane.showConfirmDialog(this, Labels.getLabel("gui.filter.preset.overwrite") + " " + name + "?",
                Labels.getLabel("gui.filter.preset.save"), JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        filterPresets.save(name, conditions);
    }

    private void showPresets() {
        JPopupMenu menu = new JPopupMenu();
        Map<String, Map<String, Set<String>>> all = filterPresets.getAll();

        if (all.isEmpty()) {
            JMenuItem empty = new JMenuItem(Labels.getLabel("gui.filter.preset.empty"));
            empty.setEnabled(false);
            menu.add(empty);
        }

        all.forEach((name, conditions) -> {
            JMenuItem item = new JMenuItem(name);
            item.setToolTipText(describe(conditions));
            item.addActionListener(e -> applyPreset(conditions));
            menu.add(item);
        });

        if (!all.isEmpty()) {
            menu.addSeparator();
            JMenu deleteMenu = new JMenu(Labels.getLabel("gui.filter.preset.delete"));
            all.keySet().forEach(name -> {
                JMenuItem item = new JMenuItem(name);
                item.addActionListener(e -> {
                    if (JOptionPane.showConfirmDialog(this, Labels.getLabel("gui.filter.preset.delete.confirm") + " " + name + "?",
                            Labels.getLabel("gui.filter.preset.delete"), JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                        filterPresets.delete(name);
                    }
                });
                deleteMenu.add(item);
            });
            menu.add(deleteMenu);
        }

        menu.show(presetsButton, 0, presetsButton.getHeight());
    }

    private void applyPreset(Map<String, Set<String>> conditions) {
        List<String> columns = getFilterColumns();
        Map<String, Set<String>> available = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        conditions.forEach((key, values) -> {
            if (columns.contains(AshFilter.colOf(key))) {
                available.put(key, values);
            } else {
                missing.add(AshFilter.colOf(key));
            }
        });

        if (!missing.isEmpty()) {
            JOptionPane.showMessageDialog(this, Labels.getLabel("gui.filter.preset.missing") + " " + String.join(", ", missing));
        }
        if (!available.isEmpty()) {
            ashFilter.replaceAll(available);
        }
    }

    private static String describe(Map<String, Set<String>> conditions) {
        return conditions.entrySet().stream()
                .map(c -> describeCondition(c.getKey(), c.getValue()))
                .collect(Collectors.joining("; "));
    }

    /**
     * "MODULE = A, B" or "SESSION_TYPE \u2260 BACKGROUND"
     */
    static String describeCondition(String key, Set<String> values) {
        String op = AshFilter.isExclusion(key) ? " \u2260 " : " = ";
        return AshFilter.colOf(key) + op + values.stream().map(FilterBar::displayValue).collect(Collectors.joining(", "));
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
