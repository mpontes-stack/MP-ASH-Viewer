package gui.filter;

import config.Labels;
import core.filter.AshFilter;
import core.filter.FilteredAshLoader;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SortOrder;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jdesktop.swingx.JXTable;

/**
 * Dialog to choose a column and one or more values (OR) for a new filter condition
 */
@Slf4j
public class AddFilterDialog extends JDialog {
    private final AshFilter ashFilter;
    private final FilteredAshLoader filteredAshLoader;
    private final Supplier<long[]> rangeSupplier;

    private final JComboBox<String> columnBox = new JComboBox<>();
    private final javax.swing.JRadioButton includeRB = new javax.swing.JRadioButton(Labels.getLabel("gui.filter.mode.include"), true);
    private final javax.swing.JRadioButton excludeRB = new javax.swing.JRadioButton(Labels.getLabel("gui.filter.mode.exclude"));
    private final JTextField searchTF = new JTextField();
    private final JLabel statusLbl = new JLabel(" ");
    private final DefaultTableModel valueModel;
    private final JXTable valueTable;
    private final JButton applyButton = new JButton(Labels.getLabel("gui.filter.apply"));
    private final JButton cancelButton = new JButton(Labels.getLabel("gui.filter.cancel"));

    private static final int COL_STORED = 2;

    private SwingWorker<Map<String, Integer>, Void> worker;

    public AddFilterDialog(Window owner, AshFilter ashFilter, FilteredAshLoader filteredAshLoader,
                           List<String> columns, Supplier<long[]> rangeSupplier) {
        super(owner, Labels.getLabel("gui.filter.dialog.title"), ModalityType.APPLICATION_MODAL);
        this.ashFilter = ashFilter;
        this.filteredAshLoader = filteredAshLoader;
        this.rangeSupplier = rangeSupplier;

        // the stored value is a hidden column: the first one shows names (PL/SQL ids) and "(empty)"
        valueModel = new DefaultTableModel(new Object[]{
                Labels.getLabel("gui.filter.value"), Labels.getLabel("gui.filter.samples"), "value"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
            @Override public Class<?> getColumnClass(int columnIndex) { return columnIndex == 1 ? Integer.class : String.class; }
        };
        valueTable = new JXTable(valueModel);
        valueTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        valueTable.setColumnControlVisible(false);
        valueTable.getColumnExt(COL_STORED).setVisible(false);
        valueTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && valueTable.getSelectedRow() >= 0) {
                    apply();
                }
            }
        });

        columnBox.setModel(new DefaultComboBoxModel<>(columns.toArray(new String[0])));
        columnBox.setMaximumRowCount(20);
        columnBox.addActionListener(e -> loadValues());

        searchTF.putClientProperty("JTextField.placeholderText", Labels.getLabel("gui.filter.search"));
        searchTF.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applySearch(); }
            @Override public void removeUpdate(DocumentEvent e) { applySearch(); }
            @Override public void changedUpdate(DocumentEvent e) { applySearch(); }
        });

        applyButton.addActionListener(e -> apply());
        cancelButton.addActionListener(e -> dispose());

        javax.swing.ButtonGroup modeGroup = new javax.swing.ButtonGroup();
        modeGroup.add(includeRB);
        modeGroup.add(excludeRB);
        includeRB.addActionListener(e -> loadValues());
        excludeRB.addActionListener(e -> loadValues());

        JPanel panel = new JPanel(new MigLayout("fill, ins 10", "[][grow]", "[][][][grow][][]"));
        panel.add(new JLabel(Labels.getLabel("gui.filter.column")));
        panel.add(columnBox, "growx, wrap");
        panel.add(new JLabel(Labels.getLabel("gui.filter.mode")));
        panel.add(includeRB, "split 2");
        panel.add(excludeRB, "wrap");
        panel.add(new JLabel(Labels.getLabel("gui.filter.value")));
        panel.add(searchTF, "growx, wrap");
        panel.add(new JScrollPane(valueTable), "span, grow, wrap");
        panel.add(statusLbl, "span, growx, wrap");
        panel.add(new JLabel(Labels.getLabel("gui.filter.hint")), "span, wrap");
        panel.add(applyButton, "span, split 2, right, tag ok");
        panel.add(cancelButton, "tag cancel");

        setContentPane(panel);
        getRootPane().setDefaultButton(applyButton);
        setSize(new Dimension(520, 520));
        setLocationRelativeTo(owner);

        loadValues();
    }

    private void loadValues() {
        String colName = (String) columnBox.getSelectedItem();
        if (colName == null) {
            return;
        }
        if (worker != null) {
            worker.cancel(true);
        }

        valueModel.setRowCount(0);
        statusLbl.setText(Labels.getLabel("gui.filter.loading"));
        applyButton.setEnabled(false);

        long[] range = rangeSupplier.get();

        worker = new SwingWorker<Map<String, Integer>, Void>() {
            @Override
            protected Map<String, Integer> doInBackground() {
                Map<String, Integer> values = filteredAshLoader.distinctValues(colName, range[0], range[1]);
                core.plsql.PlsqlNames.lookup(colName, values.keySet());
                return values;
            }

            @Override
            protected void done() {
                if (isCancelled() || this != worker) { // only the latest load fills the list
                    return;
                }
                try {
                    Map<String, Integer> values = get();
                    valueModel.setRowCount(0);
                    Set<String> selected = ashFilter.getConditions().getOrDefault(conditionKey(colName), new LinkedHashSet<>());

                    values.entrySet().stream()
                            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                            .forEach(v -> valueModel.addRow(new Object[]{
                                    FilterBar.displayValue(core.plsql.PlsqlNames.display(colName, v.getKey())),
                                    v.getValue(), v.getKey()}));

                    valueTable.setSortOrder(1, SortOrder.DESCENDING);
                    valueTable.packAll();

                    for (int i = 0; i < valueTable.getRowCount(); i++) {
                        String v = stored(i);
                        if (selected.contains(v)) {
                            valueTable.addRowSelectionInterval(i, i);
                        }
                    }

                    statusLbl.setText(values.size() + " " + Labels.getLabel("gui.filter.values.found"));
                    applyButton.setEnabled(true);
                } catch (Exception ex) {
                    log.error(ex.getMessage());
                    statusLbl.setText(ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    /** Value of a row of the table (view index) as stored in the samples */
    private String stored(int viewRow) {
        return (String) valueModel.getValueAt(valueTable.convertRowIndexToModel(viewRow), COL_STORED);
    }

    /** Key of the condition for the selected mode: "COL" (=) or "!COL" (!=) */
    private String conditionKey(String colName) {
        return excludeRB.isSelected() ? AshFilter.exclusionKey(colName) : colName;
    }

    private void applySearch() {
        String text = searchTF.getText().trim();
        if (text.isEmpty()) {
            valueTable.setRowFilter(null);
        } else {
            valueTable.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(text), 0));
        }
    }

    private void apply() {
        String colName = (String) columnBox.getSelectedItem();
        int[] rows = valueTable.getSelectedRows();
        if (colName == null || rows.length == 0) {
            return;
        }

        Set<String> values = Arrays.stream(rows)
                .mapToObj(this::stored)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        dispose();
        ashFilter.setCondition(conditionKey(colName), values);
    }
}
