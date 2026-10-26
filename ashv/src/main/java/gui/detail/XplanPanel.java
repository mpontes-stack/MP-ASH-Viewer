package gui.detail;

import config.Labels;
import core.processing.GetFromRemoteAndStore;
import gui.util.Themes;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

/**
 * DBMS_XPLAN.DISPLAY_CURSOR text of one plan (as in SQL*Plus): predicates, peeked binds, notes and, when the statement
 * ran with gather_plan_statistics or statistics_level = ALL, the actual rows. Reads the shared pool only when shown.
 */
@Slf4j
public class XplanPanel extends JPanel {
    /** DBMS_XPLAN formats, in the order of the format combo */
    private static final String[] FORMATS = {"TYPICAL +PEEKED_BINDS", "ALLSTATS LAST +COST +PEEKED_BINDS", "ADVANCED"};
    private static final Pattern FULL_SCAN = Pattern.compile("(?m)^.*(TABLE|MAT_VIEW) ACCESS (STORAGE |INMEMORY )?FULL.*$");

    private final GetFromRemoteAndStore getFromRemoteAndStore;
    private final String sqlId;
    private final long planHashValue;

    private final JComboBox<Long> child = new JComboBox<>();
    private final JComboBox<String> format = new JComboBox<>();
    private final JButton refresh = new JButton(Labels.getLabel("gui.xplan.refresh"));
    private final JButton copy = new JButton(Labels.getLabel("gui.xplan.copy"));
    private final JTextArea text = new JTextArea();
    private boolean loaded;
    private boolean filling;

    public XplanPanel(GetFromRemoteAndStore getFromRemoteAndStore, String sqlId, long planHashValue) {
        super(new BorderLayout());
        this.getFromRemoteAndStore = getFromRemoteAndStore;
        this.sqlId = sqlId;
        this.planHashValue = planHashValue;

        format.addItem(Labels.getLabel("gui.xplan.format.typical"));
        format.addItem(Labels.getLabel("gui.xplan.format.allstats"));
        format.addItem(Labels.getLabel("gui.xplan.format.advanced"));
        format.setToolTipText(Labels.getLabel("gui.xplan.format.tooltip"));
        child.setToolTipText(Labels.getLabel("gui.xplan.child.tooltip"));

        child.addActionListener(e -> { if (!filling) loadText(); });
        format.addActionListener(e -> loadText());
        refresh.addActionListener(e -> load());
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text.getText()), null));

        JPanel bar = new JPanel(new MigLayout("ins 4 6 4 6, gap 6", "[][][][]push[][]"));
        bar.add(new JLabel(Labels.getLabel("gui.xplan.child")));
        bar.add(child, "w 70!");
        bar.add(new JLabel(Labels.getLabel("gui.xplan.format")));
        bar.add(format);
        bar.add(refresh);
        bar.add(copy);

        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(text), BorderLayout.CENTER);
    }

    /** Loads on the first call only (the tab was shown) */
    public void loadOnce() {
        if (!loaded) {
            loaded = true;
            load();
        }
    }

    /** Child cursors of the plan, then the text of the selected one */
    private void load() {
        showMessage(Labels.getLabel("gui.xplan.loading"));
        new SwingWorker<List<Long>, Void>() {
            @Override
            protected List<Long> doInBackground() throws Exception {
                return getFromRemoteAndStore.getSqlChildNumbers(sqlId, planHashValue);
            }

            @Override
            protected void done() {
                try {
                    List<Long> children = get();
                    Object selected = child.getSelectedItem();
                    filling = true;
                    child.removeAllItems();
                    children.forEach(child::addItem);
                    if (selected != null && children.contains(selected)) {
                        child.setSelectedItem(selected);
                    }
                    filling = false;
                    if (children.isEmpty()) {
                        showMessage(Labels.getLabel("gui.xplan.notfound"));
                    } else {
                        loadText();
                    }
                } catch (Exception e) {
                    filling = false;
                    showMessage(Labels.getLabel("gui.xplan.error") + " " + cause(e));
                }
            }
        }.execute();
    }

    private void loadText() {
        Long childNumber = (Long) child.getSelectedItem();
        if (childNumber == null) {
            return;
        }
        String fmt = FORMATS[Math.max(0, format.getSelectedIndex())];
        showMessage(Labels.getLabel("gui.xplan.loading"));
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return getFromRemoteAndStore.getXplan(sqlId, childNumber, fmt);
            }

            @Override
            protected void done() {
                try {
                    show(get());
                } catch (Exception e) {
                    showMessage(Labels.getLabel("gui.xplan.error") + " " + cause(e));
                }
            }
        }.execute();
    }

    private void showMessage(String message) {
        text.getHighlighter().removeAllHighlights();
        text.setText(message);
    }

    /** The plan with the full table scans highlighted in the warning orange, as in the plan tree */
    private void show(String plan) {
        text.setText(plan);
        text.setCaretPosition(0);
        text.getHighlighter().removeAllHighlights();
        Color warning = Themes.isDark() ? new Color(240, 170, 60, 90) : new Color(190, 110, 0, 60);
        DefaultHighlighter.DefaultHighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(warning);
        Matcher m = FULL_SCAN.matcher(plan);
        while (m.find()) {
            try {
                text.getHighlighter().addHighlight(m.start(), m.end(), painter);
            } catch (BadLocationException e) {
                log.debug("highlight: " + e.getMessage());
            }
        }
    }

    private static String cause(Exception e) {
        Throwable t = e.getCause() != null ? e.getCause() : e;
        return t.getMessage() != null ? t.getMessage().trim() : t.toString();
    }
}
