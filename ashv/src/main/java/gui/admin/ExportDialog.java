package gui.admin;

import com.github.lgooddatepicker.components.DatePickerSettings;
import com.github.lgooddatepicker.components.DateTimePicker;
import com.github.lgooddatepicker.components.TimePickerSettings;
import config.Labels;
import java.awt.Component;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;

/** Period of a backup: all the history by default, the estimated size updates with the period */
final class ExportDialog {
    private ExportDialog() {}

    /** @return {from, to} in ms (inclusive), null when cancelled */
    static long[] ask(Component parent, String name, long first, long last, long bytes) {
        DateTimePicker from = picker();
        DateTimePicker to = picker();
        set(from, first);
        set(to, last);
        JLabel estimate = new JLabel(" ");
        Runnable update = () -> {
            long[] p = period(from, to);
            estimate.setText(p == null ? Labels.getLabel("gui.admin.export.invalid")
                    : String.format(Labels.getLabel("gui.admin.export.estimate"), AdminPanel.formatSize(size(p, first, last, bytes))));
        };
        from.addDateTimeChangeListener(e -> update.run());
        to.addDateTimeChangeListener(e -> update.run());
        JButton all = new JButton(Labels.getLabel("gui.admin.export.all"));
        all.addActionListener(e -> {
            set(from, first);
            set(to, last);
        });
        update.run();

        JPanel panel = new JPanel(new MigLayout("ins 4", "[right][][]"));
        panel.add(new JLabel(String.format(Labels.getLabel("gui.admin.export.of"), name)), "span, wrap 8");
        panel.add(new JLabel(Labels.getLabel("gui.admin.export.from")));
        panel.add(from, "wrap");
        panel.add(new JLabel(Labels.getLabel("gui.admin.export.to")));
        panel.add(to);
        panel.add(all, "gapleft 8, wrap");
        panel.add(estimate, "span, gaptop 8, wrap");
        JLabel note = new JLabel(Labels.getLabel("gui.admin.export.note"));
        note.setEnabled(false);
        panel.add(note, "span");

        while (true) {
            int choice = JOptionPane.showConfirmDialog(parent, panel, Labels.getLabel("gui.admin.export"),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) return null;
            long[] p = period(from, to);
            if (p != null) return p;
            JOptionPane.showMessageDialog(parent, Labels.getLabel("gui.admin.export.invalid"));
        }
    }

    /** Share of the size by the share of the period (samples are spread evenly enough) */
    private static long size(long[] p, long first, long last, long bytes) {
        if (last <= first) return bytes;
        double share = (double) (Math.min(p[1], last) - Math.max(p[0], first)) / (last - first);
        return Math.round(bytes * Math.max(0, Math.min(1, share)));
    }

    private static DateTimePicker picker() {
        DatePickerSettings date = new DatePickerSettings();
        date.setAllowEmptyDates(false);
        TimePickerSettings time = new TimePickerSettings();
        time.setAllowEmptyTimes(false);
        time.setFormatForDisplayTime("HH:mm:ss");
        time.setFormatForMenuTimes("HH:mm");
        return new DateTimePicker(date, time);
    }

    private static void set(DateTimePicker picker, long ms) {
        picker.setDateTimePermissive(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()));
    }

    private static long[] period(DateTimePicker from, DateTimePicker to) {
        LocalDateTime f = from.getDateTimePermissive();
        LocalDateTime t = to.getDateTimePermissive();
        if (f == null || t == null) return null;
        long a = f.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // the end picker shows seconds: the whole second is included
        long b = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() + 999;
        return b > a ? new long[]{a, b} : null;
    }
}
