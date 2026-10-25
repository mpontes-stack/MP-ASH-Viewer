package gui.admin;

import config.Labels;
import core.admin.HistoryStore;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;

/**
 * Progress of a long history task (purge, export, import): bar with %, "step: done of total (remaining)", elapsed and
 * remaining time from the speed so far, and a Cancel button. Hidden while no task runs.
 */
public class TaskProgress extends JPanel implements HistoryStore.Progress {
    // numbers in the language of the app (Locale.setDefault by gui.util.Languages)
    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance();

    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel text = new JLabel(" ");
    private final JButton cancel = new JButton(Labels.getLabel("gui.admin.cancel"));
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /** Share of the bar of each step: {start, end} */
    private Map<HistoryStore.Step, double[]> weights = new EnumMap<>(HistoryStore.Step.class);
    private volatile long start;
    private volatile boolean scheduled;
    private volatile boolean running;
    private volatile HistoryStore.Step step = HistoryStore.Step.COUNT;
    private volatile long done;
    private volatile long total = -1;

    public TaskProgress() {
        super(new MigLayout("ins 0, hidemode 3", "[250!][grow][]"));
        bar.setStringPainted(true);
        cancel.setToolTipText(Labels.getLabel("gui.admin.cancel.tooltip"));
        cancel.addActionListener(e -> {
            cancelled.set(true);
            cancel.setEnabled(false);
            text.setText(Labels.getLabel("gui.admin.cancelling"));
        });
        add(bar, "growx, h 22!");
        add(text, "growx");
        add(cancel, "gapleft 12");
        setVisible(false);
    }

    /**
     * Show the progress of a new task
     *
     * @param steps the steps of the task in order, each with its share of the bar (they add up to 1)
     */
    public void begin(Object... steps) {
        weights = new EnumMap<>(HistoryStore.Step.class);
        double at = 0;
        for (int i = 0; i + 1 < steps.length; i += 2) {
            double share = ((Number) steps[i + 1]).doubleValue();
            weights.put((HistoryStore.Step) steps[i], new double[]{at, at + share});
            at += share;
        }
        start = System.currentTimeMillis();
        step = HistoryStore.Step.COUNT;
        done = 0;
        total = -1;
        cancelled.set(false);
        cancel.setEnabled(true);
        running = true;
        bar.setValue(0);
        bar.setIndeterminate(true);
        bar.setString("");
        text.setText(" ");
        setVisible(true);
    }

    /** On the EDT, when the task ends */
    public void end() {
        running = false;
        setVisible(false);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public long elapsed() {
        return System.currentTimeMillis() - start;
    }

    public HistoryStore.Step getStep() {
        return step;
    }

    public long getDone() {
        return done;
    }

    public long getTotal() {
        return total;
    }

    @Override
    public void update(HistoryStore.Step step, long done, long total) {
        this.step = step;
        this.done = done;
        this.total = total;
        // at most a few repaints per second, whatever the number of updates
        if (!scheduled) {
            scheduled = true;
            Timer timer = new Timer(250, e -> {
                scheduled = false;
                if (running && !cancelled.get()) repaintProgress();
            });
            timer.setRepeats(false);
            timer.start();
        }
    }

    private void repaintProgress() {
        long elapsed = elapsed();
        String time = Labels.getLabel("gui.admin.progress.elapsed") + " " + clock(elapsed);
        double[] range = weights.get(step);
        if (step == HistoryStore.Step.COUNT || total <= 0 || range == null) {
            bar.setIndeterminate(true);
            bar.setString("");
            text.setText(String.format(Labels.getLabel("gui.admin.progress.count"), number(done)) + " · " + time);
            return;
        }
        double part = Math.min(1, (double) done / total);
        double overall = range[0] + (range[1] - range[0]) * part;
        bar.setIndeterminate(false);
        bar.setValue((int) Math.round(overall * 1000));
        bar.setString(Math.round(overall * 100) + "%");
        String what = String.format(Labels.getLabel("gui.admin.progress." + step.name().toLowerCase()),
                amount(done), amount(total), amount(total - done));
        String remaining = overall < 0.02 ? Labels.getLabel("gui.admin.progress.estimating")
                : "~" + clock(Math.round(elapsed * (1 - overall) / overall));
        text.setText(what + " · " + time + " · " + Labels.getLabel("gui.admin.progress.remaining") + " " + remaining);
    }

    private String amount(long v) {
        return step.bytes ? AdminPanel.formatSize(v) : number(v);
    }

    /** 05:12 or 1:05:12 */
    public static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
                : String.format("%02d:%02d", s / 60, s % 60);
    }

    public static String number(long n) {
        return new DecimalFormat("#,##0", SYMBOLS).format(n);
    }
}
