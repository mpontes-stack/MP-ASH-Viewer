package gui.admin;

import config.Labels;
import core.admin.AutoPurge;
import core.admin.HistoryStore;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.HierarchyEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jdesktop.swingx.JXTable;
import core.manager.ConfigurationManager;
import store.StoreManager;

/**
 * "Admin" tab: local history of every connection (disk space, period), purge of old data or of everything,
 * days to keep per connection (automatic purge)
 */
@Slf4j
public class AdminPanel extends JPanel {
    private static final long DAY = 24 * 3600_000L;
    private static final int VISIBLE_ROWS = 10;
    // numbers in the language of the app (Locale.setDefault by gui.util.Languages)
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance();

    private final StoreManager storeManager;
    private final HistoryModel model = new HistoryModel();
    private final JXTable table = new JXTable(model);
    private final JLabel summary = new JLabel(" ");
    private final JLabel sqlTexts = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton refresh = new JButton(Labels.getLabel("gui.admin.refresh"));
    private final JButton purgeOld = new JButton(Labels.getLabel("gui.admin.purge.old"));
    private final JButton purgeAll = new JButton(Labels.getLabel("gui.admin.purge.all"));
    private final JButton export = new JButton(Labels.getLabel("gui.admin.export"));
    private final TaskProgress progress = new TaskProgress();
    private final ConfigurationManager configurationManager;
    private boolean loaded;
    private volatile int generation;

    public AdminPanel(StoreManager storeManager) {
        super(new BorderLayout());
        this.storeManager = storeManager;
        this.configurationManager = storeManager.getConfigurationManager();

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setColumnControlVisible(true);
        table.setDefaultRenderer(Long.class, new ValueRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());

        refresh.addActionListener(e -> load());
        purgeOld.setToolTipText(Labels.getLabel("gui.admin.purge.old.tooltip"));
        purgeOld.addActionListener(e -> purgeOld());
        purgeAll.setToolTipText(Labels.getLabel("gui.admin.purge.all.tooltip"));
        purgeAll.addActionListener(e -> purgeAll());
        export.setToolTipText(Labels.getLabel("gui.admin.export.tooltip"));
        export.addActionListener(e -> export());

        JPanel top = new JPanel(new MigLayout("fillx, ins 8 8 4 8", "[grow, fill]"));
        top.add(gui.util.Ui.title(Labels.getLabel("gui.admin.history")), "split 2, gapbottom 2");
        top.add(new JSeparator(), "growx, wrap");
        top.add(summary, "wrap");
        top.add(sqlTexts, "wrap");
        JLabel help = new JLabel(Labels.getLabel("gui.admin.keep.help"));
        help.setEnabled(false);
        top.add(help, "wrap");

        JPanel buttons = new JPanel(new MigLayout("ins 4 8 8 8, hidemode 3", "[][][]16[]16[grow]"));
        buttons.add(refresh);
        buttons.add(purgeOld);
        buttons.add(purgeAll);
        buttons.add(export);
        buttons.add(status, "growx, wrap");
        buttons.add(progress, "span, growx");

        // a short list with its own scroll bar: the tab has room for other sections (backup) below
        table.setVisibleRowCount(VISIBLE_ROWS);
        int[] widths = {280, 80, 120, 120, 50, 95, 200};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        JPanel history = new JPanel(new BorderLayout());
        history.add(top, BorderLayout.NORTH);
        history.add(new JScrollPane(table), BorderLayout.CENTER);
        history.add(buttons, BorderLayout.SOUTH);
        // not wider than needed: the columns stay readable on wide screens
        JPanel narrow = new JPanel(new MigLayout("ins 0", "[fill, 0:1050:1050]"));
        narrow.add(history, "growx");
        add(narrow, BorderLayout.NORTH);
        updateButtons();

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && !loaded) {
                loaded = true;
                SwingUtilities.invokeLater(this::load);
            }
        });
    }

    /** Sizes now, periods in the background (each history is opened read only) */
    private void load() {
        int gen = ++generation;
        List<HistoryStore.Info> infos = HistoryStore.list();
        model.set(infos);
        long total = infos.stream().mapToLong(HistoryStore.Info::getBytes).sum();
        long texts = HistoryStore.sqlTextCount(storeManager);
        summary.setText(String.format(Labels.getLabel("gui.admin.summary"), infos.size(), formatSize(total),
                HistoryStore.databaseDir().getAbsolutePath()));
        sqlTexts.setText(texts >= 0 ? String.format(Labels.getLabel("gui.admin.sqltexts"), number(texts)) : " ");
        status.setText(Labels.getLabel("gui.admin.reading"));
        Thread t = new Thread(() -> {
            for (int i = 0; i < infos.size() && gen == generation; i++) {
                HistoryStore.readPeriod(infos.get(i), storeManager);
                int row = i;
                SwingUtilities.invokeLater(() -> model.fireTableRowsUpdated(row, row));
            }
            SwingUtilities.invokeLater(() -> {
                if (gen == generation) status.setText(" ");
            });
        }, "admin-history");
        t.setDaemon(true);
        t.start();
        updateButtons();
    }

    private HistoryStore.Info selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.infos.get(table.convertRowIndexToModel(row));
    }

    private void updateButtons() {
        HistoryStore.Info info = selected();
        boolean busy = AutoPurge.isRunning();
        refresh.setEnabled(!busy);
        purgeOld.setEnabled(!busy && info != null && info.getFirst() > 0);
        purgeAll.setEnabled(!busy && info != null && !HistoryStore.isOpen(info.getName(), storeManager));
        export.setEnabled(!busy && info != null && info.getFirst() > 0);
    }

    private void purgeOld() {
        HistoryStore.Info info = selected();
        if (info == null) return;
        int keep = HistoryStore.getKeepDays(info.getName());
        String answer = (String) JOptionPane.showInputDialog(this,
                String.format(Labels.getLabel("gui.admin.purge.old.ask"), info.getName()),
                Labels.getLabel("gui.admin.purge.old"), JOptionPane.QUESTION_MESSAGE, null, null,
                String.valueOf(keep > 0 ? keep : 30));
        int days = parseDays(answer);
        if (days <= 0) return;
        long cutoff = System.currentTimeMillis() - days * DAY;
        if (cutoff <= info.getFirst()) {
            JOptionPane.showMessageDialog(this, String.format(Labels.getLabel("gui.admin.purge.nothing"), days));
            return;
        }
        if (!confirm(String.format(Labels.getLabel("gui.admin.purge.old.confirm"), info.getName(), days,
                date(cutoff), formatSize(estimate(info, cutoff))))) return;
        purge(info, cutoff);
    }

    private void purge(HistoryStore.Info info, long cutoff) {
        progress.begin(HistoryStore.Step.DELETE, 0.6, HistoryStore.Step.COMPACT, 0.4);
        run(info, Labels.getLabel("gui.admin.purging"), () -> {
            long n = HistoryStore.purgeOlder(info.getName(), cutoff, storeManager, progress::isCancelled, progress);
            return n < 0 ? cancelledText() : String.format(Labels.getLabel("gui.admin.purge.done"), n, info.getName());
        });
    }

    /** Period (all of it by default) and file of the backup of the selected connection */
    private void export() {
        HistoryStore.Info info = selected();
        if (info == null || info.getFirst() <= 0) return;
        long[] period = ExportDialog.ask(this, info.getName(), info.getFirst(), info.getLast(), info.getBytes());
        if (period == null) return;
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle(Labels.getLabel("gui.admin.export"));
        chooser.setSelectedFile(new java.io.File(core.admin.HistoryBackup.defaultDir(),
                core.admin.HistoryBackup.fileName(info.getName(), period[0], period[1])));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File zip = chooser.getSelectedFile().getName().toLowerCase().endsWith(".zip") ? chooser.getSelectedFile()
                : new java.io.File(chooser.getSelectedFile().getPath() + ".zip");
        if (zip.exists() && !confirm(String.format(Labels.getLabel("gui.admin.export.replace"), zip.getName()))) return;
        progress.begin(HistoryStore.Step.COPY, 0.85, HistoryStore.Step.ZIP, 0.15);
        run(info, Labels.getLabel("gui.admin.exporting"), () -> {
            long n = core.admin.HistoryBackup.export(info.getName(), period[0], period[1], zip, storeManager,
                    configurationManager, progress::isCancelled, progress);
            return n < 0 ? Labels.getLabel("gui.admin.export.cancelled")
                    : String.format(Labels.getLabel("gui.admin.export.done"), number(n), zip.getAbsolutePath(), formatSize(zip.length()));
        });
    }

    private void purgeAll() {
        HistoryStore.Info info = selected();
        if (info == null || HistoryStore.isOpen(info.getName(), storeManager)) return;
        if (!confirm(String.format(Labels.getLabel("gui.admin.purge.all.confirm"), info.getName(), formatSize(info.getBytes()))))
            return;
        progress.begin();
        run(info, Labels.getLabel("gui.admin.purging"), () -> {
            HistoryStore.deleteAll(info.getName(), storeManager);
            return String.format(Labels.getLabel("gui.admin.purge.all.done"), info.getName());
        });
    }

    interface Task {
        String run() throws Exception;
    }

    /** One purge at a time (also with the automatic one), in the background, then the list is read again */
    private void run(HistoryStore.Info info, String message, Task task) {
        if (!AutoPurge.begin()) {
            JOptionPane.showMessageDialog(this, Labels.getLabel("gui.admin.busy"));
            return;
        }
        status.setText(message + " " + info.getName() + "...");
        updateButtons();
        Thread t = new Thread(() -> {
            String result;
            try {
                result = task.run();
            } catch (Exception e) {
                log.error("Admin " + info.getName(), e);
                result = Labels.getLabel("gui.admin.error") + " " + e.getMessage();
            } finally {
                AutoPurge.end();
            }
            String text = result + " (" + Labels.getLabel("gui.admin.progress.elapsed") + " "
                    + TaskProgress.clock(progress.elapsed()) + ")";
            SwingUtilities.invokeLater(() -> {
                progress.end();
                load();
                status.setText(text);
            });
        }, "admin-task");
        t.setDaemon(true);
        t.start();
    }

    /** Cancelled: what was deleted until then stays deleted, the rest is kept */
    private String cancelledText() {
        return progress.getStep() == HistoryStore.Step.COMPACT ? Labels.getLabel("gui.admin.cancelled.compact")
                : progress.getStep() == HistoryStore.Step.COUNT ? Labels.getLabel("gui.admin.cancelled.nothing")
                : String.format(Labels.getLabel("gui.admin.cancelled.delete"), number(progress.getDone()), number(progress.getTotal()));
    }

    static String number(long n) {
        return TaskProgress.number(n);
    }

    /** "Manter (dias)" edited: saved; offered to apply now when it deletes something */
    private void keepDaysChanged(HistoryStore.Info info, int days) {
        int before = HistoryStore.getKeepDays(info.getName());
        if (days == before) return;
        long cutoff = days > 0 ? System.currentTimeMillis() - days * DAY : 0;
        if (days > 0 && info.getFirst() > 0 && cutoff > info.getFirst()) {
            Object[] options = {Labels.getLabel("gui.admin.keep.now"), Labels.getLabel("gui.admin.keep.later"),
                    Labels.getLabel("gui.admin.cancel")};
            int choice = JOptionPane.showOptionDialog(this,
                    String.format(Labels.getLabel("gui.admin.keep.confirm"), info.getName(), days, date(cutoff),
                            formatSize(estimate(info, cutoff))),
                    Labels.getLabel("gui.admin.col.keep"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                    null, options, options[2]);
            if (choice != 0 && choice != 1) return;
            HistoryStore.setKeepDays(info.getName(), days);
            if (choice == 0) {
                purge(info, cutoff);
            }
        } else {
            HistoryStore.setKeepDays(info.getName(), days);
        }
        model.fireTableDataChanged();
    }

    private boolean confirm(String text) {
        Object[] options = {Labels.getLabel("gui.admin.confirm"), Labels.getLabel("gui.admin.cancel")};
        return JOptionPane.showOptionDialog(this, text, Labels.getLabel("gui.admin.tab"), JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0;
    }

    /** Space of the samples older than the cutoff, as a share of the period (samples are spread evenly enough) */
    private static long estimate(HistoryStore.Info info, long cutoff) {
        if (info.getLast() <= info.getFirst()) return 0;
        double share = Math.min(1, Math.max(0, (double) (cutoff - info.getFirst()) / (info.getLast() - info.getFirst())));
        return Math.round(info.getBytes() * share);
    }

    private static int parseDays(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String formatSize(long bytes) {
        if (bytes >= 1L << 30) return new DecimalFormat("0.0", SYMBOLS).format(bytes / (double) (1L << 30)) + " GB";
        if (bytes >= 1L << 20) return new DecimalFormat("0", SYMBOLS).format(bytes / (double) (1L << 20)) + " MB";
        return new DecimalFormat("0", SYMBOLS).format(bytes / 1024.0) + " KB";
    }

    static String date(long ms) {
        return new SimpleDateFormat("dd/MM/yyyy HH:mm").format(new Date(ms));
    }

    private final class HistoryModel extends AbstractTableModel {
        static final int COL_NAME = 0;
        static final int COL_SIZE = 1;
        static final int COL_FIRST = 2;
        static final int COL_LAST = 3;
        static final int COL_DAYS = 4;
        static final int COL_KEEP = 5;
        static final int COL_STATE = 6;

        private List<HistoryStore.Info> infos = new ArrayList<>();

        void set(List<HistoryStore.Info> infos) {
            this.infos = infos;
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return infos.size(); }
        @Override public int getColumnCount() { return 7; }

        @Override
        public String getColumnName(int column) {
            switch (column) {
                case COL_NAME: return Labels.getLabel("gui.admin.col.name");
                case COL_SIZE: return Labels.getLabel("gui.admin.col.size");
                case COL_FIRST: return Labels.getLabel("gui.admin.col.first");
                case COL_LAST: return Labels.getLabel("gui.admin.col.last");
                case COL_DAYS: return Labels.getLabel("gui.admin.col.days");
                case COL_KEEP: return Labels.getLabel("gui.admin.col.keep");
                default: return Labels.getLabel("gui.admin.col.state");
            }
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == COL_NAME || column == COL_STATE ? String.class : column == COL_KEEP ? Integer.class : Long.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == COL_KEEP && !AutoPurge.isRunning();
        }

        @Override
        public Object getValueAt(int row, int column) {
            HistoryStore.Info info = infos.get(row);
            switch (column) {
                case COL_NAME: return info.getName();
                case COL_SIZE: return info.getBytes();
                case COL_FIRST: return info.getFirst();
                case COL_LAST: return info.getLast();
                case COL_DAYS: return info.getFirst() > 0 ? (info.getLast() - info.getFirst()) / DAY : -1L;
                case COL_KEEP: {
                    int days = HistoryStore.getKeepDays(info.getName());
                    return days > 0 ? days : null;
                }
                default:
                    if (info.getError() != null) return Labels.getLabel("gui.admin.state.error") + " " + info.getError();
                    return HistoryStore.isOpen(info.getName(), storeManager) ? Labels.getLabel("gui.admin.state.open") : "";
            }
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == COL_KEEP) {
                keepDaysChanged(infos.get(row), value instanceof Integer ? (Integer) value : 0);
            }
        }
    }

    /** Size, dates and days by column; empty while the period is being read */
    private static final class ValueRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            long v = value instanceof Long ? (Long) value : 0;
            int col = table.convertColumnIndexToModel(column);
            setHorizontalAlignment(col == HistoryModel.COL_SIZE || col == HistoryModel.COL_DAYS ? SwingConstants.RIGHT : SwingConstants.LEFT);
            if (col == HistoryModel.COL_SIZE) {
                setText(formatSize(v));
            } else if (col == HistoryModel.COL_DAYS) {
                setText(v < 0 ? "" : v == 0 ? "< 1" : String.valueOf(v));
            } else {
                setText(v > 0 ? date(v) : "");
            }
            return this;
        }
    }
}
