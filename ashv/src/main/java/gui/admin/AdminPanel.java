package gui.admin;

import config.Labels;
import core.admin.AutoPurge;
import core.admin.HistoryStore;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
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
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;
import org.jdesktop.swingx.JXTable;
import store.StoreManager;

/**
 * "Admin" tab: local history of every connection (disk space, period), purge of old data or of everything,
 * days to keep per connection (automatic purge)
 */
@Slf4j
public class AdminPanel extends JPanel {
    private static final long DAY = 24 * 3600_000L;
    private static final DecimalFormatSymbols PT_BR = DecimalFormatSymbols.getInstance(new Locale("pt", "BR"));

    private final StoreManager storeManager;
    private final HistoryModel model = new HistoryModel();
    private final JXTable table = new JXTable(model);
    private final JLabel summary = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JButton refresh = new JButton(Labels.getLabel("gui.admin.refresh"));
    private final JButton purgeOld = new JButton(Labels.getLabel("gui.admin.purge.old"));
    private final JButton purgeAll = new JButton(Labels.getLabel("gui.admin.purge.all"));
    private final JProgressBar progressBar = new JProgressBar(0, 1000);
    private final JButton cancel = new JButton(Labels.getLabel("gui.admin.cancel"));
    private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    private final JLabel progressText = new JLabel(" ");
    private boolean loaded;
    private volatile int generation;

    public AdminPanel(StoreManager storeManager) {
        super(new BorderLayout());
        this.storeManager = storeManager;

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setColumnControlVisible(true);
        table.setDefaultRenderer(Long.class, new ValueRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());

        refresh.addActionListener(e -> load());
        purgeOld.setToolTipText(Labels.getLabel("gui.admin.purge.old.tooltip"));
        purgeOld.addActionListener(e -> purgeOld());
        purgeAll.setToolTipText(Labels.getLabel("gui.admin.purge.all.tooltip"));
        purgeAll.addActionListener(e -> purgeAll());

        JPanel top = new JPanel(new MigLayout("fillx, ins 8 8 4 8", "[grow, fill]"));
        top.add(title(Labels.getLabel("gui.admin.history")), "split 2, gapbottom 2");
        top.add(new JSeparator(), "growx, wrap");
        top.add(summary, "wrap");
        JLabel help = new JLabel(Labels.getLabel("gui.admin.keep.help"));
        help.setEnabled(false);
        top.add(help, "wrap");

        JPanel buttons = new JPanel(new MigLayout("ins 4 8 8 8, hidemode 3", "[][][]16[grow]"));
        buttons.add(refresh);
        buttons.add(purgeOld);
        buttons.add(purgeAll);
        buttons.add(status, "growx, wrap");
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        progressText.setVisible(false);
        cancel.setToolTipText(Labels.getLabel("gui.admin.cancel.tooltip"));
        cancel.setVisible(false);
        cancel.addActionListener(e -> {
            cancelled.set(true);
            cancel.setEnabled(false);
            status.setText(Labels.getLabel("gui.admin.cancelling"));
        });
        buttons.add(progressBar, "span 3, growx, h 22!");
        buttons.add(progressText, "growx, split 2");
        buttons.add(cancel, "gapleft 12");

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
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
        summary.setText(String.format(Labels.getLabel("gui.admin.summary"), infos.size(), formatSize(total),
                HistoryStore.databaseDir().getAbsolutePath()));
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
        run(info, Labels.getLabel("gui.admin.purging"), progress -> {
            long n = HistoryStore.purgeOlder(info.getName(), cutoff, storeManager, cancelled::get, progress);
            return n < 0 ? cancelledText(progress) : String.format(Labels.getLabel("gui.admin.purge.done"), n, info.getName());
        });
    }

    private void purgeAll() {
        HistoryStore.Info info = selected();
        if (info == null || HistoryStore.isOpen(info.getName(), storeManager)) return;
        if (!confirm(String.format(Labels.getLabel("gui.admin.purge.all.confirm"), info.getName(), formatSize(info.getBytes()))))
            return;
        run(info, Labels.getLabel("gui.admin.purging"), progress -> {
            HistoryStore.deleteAll(info.getName(), storeManager);
            return String.format(Labels.getLabel("gui.admin.purge.all.done"), info.getName());
        });
    }

    interface Task {
        String run(HistoryStore.Progress progress) throws Exception;
    }

    /** One purge at a time (also with the automatic one), in the background, then the list is read again */
    private void run(HistoryStore.Info info, String message, Task task) {
        if (!AutoPurge.begin()) {
            JOptionPane.showMessageDialog(this, Labels.getLabel("gui.admin.busy"));
            return;
        }
        status.setText(message + " " + info.getName() + "...");
        updateButtons();
        Tracker tracker = new Tracker();
        cancelled.set(false);
        cancel.setEnabled(true);
        cancel.setVisible(true);
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        progressBar.setString("");
        progressText.setText(" ");
        progressBar.setVisible(true);
        progressText.setVisible(true);
        Thread t = new Thread(() -> {
            String result;
            try {
                result = task.run(tracker);
            } catch (Exception e) {
                log.error("Admin " + info.getName(), e);
                result = Labels.getLabel("gui.admin.error") + " " + e.getMessage();
            } finally {
                AutoPurge.end();
            }
            String text = result + " (" + Labels.getLabel("gui.admin.progress.elapsed") + " " + clock(tracker.elapsed()) + ")";
            SwingUtilities.invokeLater(() -> {
                tracker.finished = true;
                cancel.setVisible(false);
                progressBar.setVisible(false);
                progressText.setVisible(false);
                load();
                status.setText(text);
            });
        }, "admin-purge");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Progress of a purge: deleting is 60% of the bar, giving the space back 40%; remaining time from the speed so far
     */
    private final class Tracker implements HistoryStore.Progress {
        private final long start = System.currentTimeMillis();
        private volatile boolean scheduled;
        private volatile boolean finished;
        private volatile HistoryStore.Step step = HistoryStore.Step.COUNT;
        private volatile long done;
        private volatile long total = -1;

        long elapsed() {
            return System.currentTimeMillis() - start;
        }

        @Override
        public void update(HistoryStore.Step step, long done, long total) {
            this.step = step;
            this.done = done;
            this.total = total;
            // at most a few repaints per second, whatever the number of updates
            if (!scheduled) {
                scheduled = true;
                javax.swing.Timer timer = new javax.swing.Timer(250, e -> {
                    scheduled = false;
                    if (!finished) show();
                });
                timer.setRepeats(false);
                timer.start();
            }
        }

        private void show() {
            long elapsed = elapsed();
            String time = Labels.getLabel("gui.admin.progress.elapsed") + " " + clock(elapsed);
            if (step == HistoryStore.Step.COUNT || total <= 0) {
                progressBar.setIndeterminate(true);
                progressBar.setString("");
                progressText.setText(String.format(Labels.getLabel("gui.admin.progress.count"), number(done)) + " · " + time);
                return;
            }
            double part = Math.min(1, (double) done / total);
            double overall = step == HistoryStore.Step.DELETE ? 0.6 * part : 0.6 + 0.4 * part;
            progressBar.setIndeterminate(false);
            progressBar.setValue((int) Math.round(overall * 1000));
            progressBar.setString(Math.round(overall * 100) + "%");
            String what = step == HistoryStore.Step.DELETE
                    ? String.format(Labels.getLabel("gui.admin.progress.delete"), number(done), number(total), number(total - done))
                    : String.format(Labels.getLabel("gui.admin.progress.compact"), formatSize(done), formatSize(total),
                    formatSize(total - done));
            String remaining = overall < 0.02 ? Labels.getLabel("gui.admin.progress.estimating")
                    : "~" + clock(Math.round(elapsed * (1 - overall) / overall));
            progressText.setText(what + " · " + time + " · " + Labels.getLabel("gui.admin.progress.remaining") + " " + remaining);
        }
    }

    /** Cancelled: what was deleted until then stays deleted, the rest is kept */
    private static String cancelledText(HistoryStore.Progress progress) {
        Tracker t = (Tracker) progress;
        return t.step == HistoryStore.Step.COMPACT ? Labels.getLabel("gui.admin.cancelled.compact")
                : t.step == HistoryStore.Step.COUNT ? Labels.getLabel("gui.admin.cancelled.nothing")
                : String.format(Labels.getLabel("gui.admin.cancelled.delete"), number(t.done), number(t.total));
    }

    /** 05:12 or 1:05:12 */
    static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    static String number(long n) {
        return new DecimalFormat("#,##0", PT_BR).format(n);
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
                run(info, Labels.getLabel("gui.admin.purging"), progress -> {
                    long n = HistoryStore.purgeOlder(info.getName(), cutoff, storeManager, cancelled::get, progress);
                    return n < 0 ? cancelledText(progress) : String.format(Labels.getLabel("gui.admin.purge.done"), n, info.getName());
                });
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

    private static JLabel title(String text) {
        JLabel title = new JLabel(text);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setForeground(UIManager.getColor("Component.accentColor") != null
                ? UIManager.getColor("Component.accentColor") : title.getForeground());
        return title;
    }

    static String formatSize(long bytes) {
        if (bytes >= 1L << 30) return new DecimalFormat("0.0", PT_BR).format(bytes / (double) (1L << 30)) + " GB";
        if (bytes >= 1L << 20) return new DecimalFormat("0", PT_BR).format(bytes / (double) (1L << 20)) + " MB";
        return new DecimalFormat("0", PT_BR).format(bytes / 1024.0) + " KB";
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
