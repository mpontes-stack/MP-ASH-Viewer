package gui.admin;

import config.Labels;
import config.profile.ConfigProfile;
import core.admin.HistoryBackup;
import core.manager.ConfigurationManager;
import java.awt.Color;
import java.awt.Frame;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

/**
 * Import of a history backup (.zip) from the connection dialog: into a new connection (group "Importados"), into a
 * connection with an empty history, or into the connection of the same name when the period does not overlap
 */
@Slf4j
public final class ImportDialog extends JDialog {
    private final ConfigurationManager configurationManager;
    private final Runnable onImported;
    private final File zip;
    private final HistoryBackup.Manifest manifest;

    private final JRadioButton toNew = new JRadioButton(Labels.getLabel("gui.import.to.new"));
    private final JRadioButton toExisting = new JRadioButton(Labels.getLabel("gui.import.to.existing"));
    private final JTextField newName = new JTextField(28);
    private final JComboBox<String> existing = new JComboBox<>();
    private final JLabel check = new JLabel(" ");
    private final JButton importButton = new JButton(Labels.getLabel("gui.import.button"));
    private final JButton close = new JButton(Labels.getLabel("gui.import.close"));
    private final TaskProgress progress = new TaskProgress();
    private boolean running;

    /** Choose the file, then the target */
    public static void open(Frame owner, ConfigurationManager configurationManager, Runnable onImported) {
        JFileChooser chooser = new JFileChooser(HistoryBackup.defaultDir());
        chooser.setDialogTitle(Labels.getLabel("gui.import.title"));
        chooser.setFileFilter(new FileNameExtensionFilter(Labels.getLabel("gui.import.filter"), "zip"));
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        File zip = chooser.getSelectedFile();
        HistoryBackup.Manifest manifest;
        try {
            manifest = HistoryBackup.readManifest(zip);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(owner, Labels.getLabel("gui.import.invalid") + "\n" + e.getMessage(),
                    Labels.getLabel("gui.import.title"), JOptionPane.ERROR_MESSAGE);
            return;
        }
        new ImportDialog(owner, configurationManager, onImported, zip, manifest).setVisible(true);
    }

    private ImportDialog(Frame owner, ConfigurationManager configurationManager, Runnable onImported, File zip,
                         HistoryBackup.Manifest manifest) {
        super(owner, Labels.getLabel("gui.import.title"), true);
        this.configurationManager = configurationManager;
        this.onImported = onImported;
        this.zip = zip;
        this.manifest = manifest;

        SimpleDateFormat f = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
        JPanel info = new JPanel(new MigLayout("ins 6", "[right][]"));
        info.setBorder(BorderFactory.createTitledBorder(Labels.getLabel("gui.import.backup")));
        row(info, "gui.import.file", zip.getName());
        row(info, "gui.import.origin", manifest.getOrigin());
        row(info, "gui.import.profile", manifest.getProfile());
        row(info, "gui.import.period", f.format(new Date(manifest.getFrom())) + "  →  " + f.format(new Date(manifest.getTo())));
        row(info, "gui.import.samples", TaskProgress.number(manifest.getSamples()) + "   ·   "
                + Labels.getLabel("gui.import.texts") + " " + TaskProgress.number(manifest.getSqlTexts()));
        row(info, "gui.import.exported", manifest.getExportedAt());

        List<String> names = configurationManager.getConnProfileList().stream().map(ConfigProfile::getConfigName)
                .sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        names.forEach(existing::addItem);
        String suggested = manifest.getOrigin();
        if (names.stream().anyMatch(n -> n.equalsIgnoreCase(manifest.getOrigin()))) {
            existing.setSelectedItem(names.stream().filter(n -> n.equalsIgnoreCase(manifest.getOrigin())).findFirst().get());
            suggested = manifest.getOrigin() + " (importado)";
        }
        newName.setText(suggested);
        ButtonGroup group = new ButtonGroup();
        group.add(toNew);
        group.add(toExisting);
        toNew.setSelected(true);

        JPanel target = new JPanel(new MigLayout("ins 6", "[][grow, fill]"));
        target.setBorder(BorderFactory.createTitledBorder(Labels.getLabel("gui.import.to")));
        target.add(toNew);
        target.add(newName, "wrap");
        target.add(toExisting);
        target.add(existing, "wrap");
        JLabel rules = new JLabel(Labels.getLabel("gui.import.rules"));
        rules.setEnabled(false);
        target.add(rules, "span, wrap");
        target.add(check, "span, gaptop 6");

        importButton.addActionListener(e -> runImport());
        close.addActionListener(e -> dispose());
        toNew.addActionListener(e -> validateTarget());
        toExisting.addActionListener(e -> validateTarget());
        existing.addActionListener(e -> validateTarget());
        newName.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { validateTarget(); }
            @Override public void removeUpdate(DocumentEvent e) { validateTarget(); }
            @Override public void changedUpdate(DocumentEvent e) { validateTarget(); }
        });

        JPanel content = new JPanel(new MigLayout("ins 10, fillx", "[grow, fill]"));
        content.add(info, "wrap");
        content.add(target, "wrap");
        content.add(progress, "growx, wrap");
        content.add(importButton, "split 2, right, tag ok");
        content.add(close, "tag cancel");
        setContentPane(content);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        validateTarget();
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private static void row(JPanel panel, String label, String value) {
        panel.add(new JLabel(Labels.getLabel(label)));
        JLabel v = new JLabel(value);
        v.setFont(v.getFont().deriveFont(java.awt.Font.BOLD));
        panel.add(v, "wrap");
    }

    private String targetName() {
        return toNew.isSelected() ? newName.getText().trim() : (String) existing.getSelectedItem();
    }

    /** The rules are checked while the target is chosen: the button is enabled only when the import can run */
    private void validateTarget() {
        if (running) return;
        newName.setEnabled(toNew.isSelected());
        existing.setEnabled(toExisting.isSelected());
        String name = targetName();
        String problem;
        if (name == null || name.isEmpty()) {
            problem = Labels.getLabel("gui.import.noname");
        } else if (name.matches(".*[\\\\/:*?\"<>|].*")) {
            problem = Labels.getLabel("gui.import.badname");
        } else if (toNew.isSelected() && configurationManager.getConnProfileList().stream()
                .anyMatch(c -> c.getConfigName().equalsIgnoreCase(name))) {
            problem = Labels.getLabel("gui.import.exists");
        } else {
            problem = HistoryBackup.check(manifest, name, configurationManager, null);
        }
        boolean ok = problem == null;
        check.setForeground(ok ? new Color(30, 140, 60) : new Color(200, 110, 0));
        check.setText("<html>" + (ok ? "✔ " + Labels.getLabel("gui.import.ok") : "⚠ " + problem) + "</html>");
        importButton.setEnabled(ok);
    }

    private void runImport() {
        String name = targetName();
        boolean isNew = toNew.isSelected();
        running = true;
        importButton.setEnabled(false);
        close.setEnabled(false);
        toNew.setEnabled(false);
        toExisting.setEnabled(false);
        newName.setEnabled(false);
        existing.setEnabled(false);
        if (HistoryBackup.hasSamples(name)) {
            progress.begin(core.admin.HistoryStore.Step.UNZIP, 0.3, core.admin.HistoryStore.Step.MERGE, 0.7);
        } else {
            progress.begin(core.admin.HistoryStore.Step.UNZIP, 1.0);
        }
        Thread t = new Thread(() -> {
            String message;
            boolean done = false;
            try {
                long n = HistoryBackup.importTo(zip, manifest, name, isNew, configurationManager, progress::isCancelled, progress);
                done = n >= 0;
                message = n < 0 ? Labels.getLabel("gui.import.cancelled")
                        : String.format(Labels.getLabel("gui.import.done"), TaskProgress.number(n), name);
            } catch (Exception e) {
                log.error("Import " + zip, e);
                message = Labels.getLabel("gui.admin.error") + " " + e.getMessage();
            }
            String text = message + "\n(" + Labels.getLabel("gui.admin.progress.elapsed") + " " + TaskProgress.clock(progress.elapsed()) + ")";
            boolean imported = done;
            SwingUtilities.invokeLater(() -> {
                progress.end();
                running = false;
                if (imported && onImported != null) onImported.run();
                JOptionPane.showMessageDialog(this, text, Labels.getLabel("gui.import.title"),
                        imported ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                if (imported) {
                    dispose();
                } else {
                    close.setEnabled(true);
                    toNew.setEnabled(true);
                    toExisting.setEnabled(true);
                    validateTarget();
                }
            });
        }, "history-import");
        t.setDaemon(true);
        t.start();
    }
}
