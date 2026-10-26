package gui.custom;

import config.Labels;
import gui.util.Languages;
import gui.util.Themes;
import gui.util.Ui;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import lombok.extern.slf4j.Slf4j;
import net.miginfocom.swing.MigLayout;

/**
 * "About" window: logo, version, project page, credits, license, libraries and the system (copied for bug reports)
 */
@Slf4j
public final class AboutDialog {
    public static final String PROJECT_URL = "https://github.com/mpontes-stack/MP-ASH-Viewer";
    private static final String MAINTAINER_EMAIL = "marco.pontes.app@gmail.com";
    private static final String UPSTREAM_URL = "https://github.com/akardapolov/ASH-Viewer";
    private static final String LICENSE_URL = "https://www.gnu.org/licenses/gpl-3.0.html";
    private static final String LIBRARIES = "JFreeChart · E-Gantt · Berkeley DB Java Edition · SwingX · FlatLaf · "
            + "MigLayout · LGoodDatePicker · Dagger 2 · Bouncy Castle FIPS · SnakeYAML";

    private AboutDialog() {}

    public static void show(Frame owner) {
        JDialog dialog = new JDialog(owner, Labels.getLabel("gui.about.title"), true);
        JButton copy = new JButton(Labels.getLabel("gui.about.copy"));
        copy.setToolTipText(Labels.getLabel("gui.about.copy.tooltip"));
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(systemInfo()), null);
            copy.setText(Labels.getLabel("gui.about.copied"));
        });
        JButton close = new JButton(Labels.getLabel("gui.import.close"));
        close.addActionListener(e -> dialog.dispose());

        JPanel panel = content();
        panel.add(copy, "span, split 2, right, gaptop 12");
        panel.add(close);
        dialog.setContentPane(panel);
        dialog.getRootPane().setDefaultButton(close);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** The content of the window (without the buttons) */
    public static JPanel content() {
        JPanel panel = new JPanel(new MigLayout("ins 16 20 12 20, gap 8 4", "[right][left, grow]"));
        JLabel logo = AppLogo.label(40, 8);
        panel.add(logo, "span, center, wrap");
        JLabel description = new JLabel(Labels.getLabel("gui.about.description"));
        panel.add(description, "span, center, wrap 12");

        section(panel, Labels.getLabel("gui.about.project"));
        row(panel, Labels.getLabel("gui.about.page"), link(PROJECT_URL, PROJECT_URL));
        row(panel, Labels.getLabel("gui.about.version"), new JLabel(AppLogo.NAME + " " + AppLogo.VERSION));
        row(panel, Labels.getLabel("gui.about.maintainer"), link("Marco Pontes · " + MAINTAINER_EMAIL, "mailto:" + MAINTAINER_EMAIL));

        section(panel, Labels.getLabel("gui.about.credits"));
        row(panel, Labels.getLabel("gui.about.basedon"), link("ASH Viewer · Alexander Kardapolov (akardapolov)", UPSTREAM_URL));
        row(panel, Labels.getLabel("gui.about.license"), link("GNU General Public License v3.0", LICENSE_URL));
        JLabel libraries = new JLabel("<html><div style='width:420px'>" + LIBRARIES + "</div></html>");
        row(panel, Labels.getLabel("gui.about.libraries"), libraries);

        section(panel, Labels.getLabel("gui.about.system"));
        row(panel, "Java", new JLabel(System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"));
        row(panel, Labels.getLabel("gui.about.os"), new JLabel(System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch")));
        row(panel, Labels.getLabel("gui.about.memory"), new JLabel((Runtime.getRuntime().maxMemory() >> 20) + " MB (-Xmx)"));
        row(panel, Labels.getLabel("gui.about.folder"), new JLabel(new File(".").getAbsoluteFile().getParent()));
        row(panel, Labels.getLabel("gui.about.settings"), new JLabel(Labels.getLabel("language." + Languages.current())
                + " · " + Labels.getLabel("gui.theme." + Themes.current())));
        return panel;
    }

    /** What a bug report needs */
    public static String systemInfo() {
        return AppLogo.NAME + " " + AppLogo.VERSION + "\n"
                + PROJECT_URL + " · " + MAINTAINER_EMAIL + "\n"
                + "Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
                + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + "\n"
                + "Max memory: " + (Runtime.getRuntime().maxMemory() >> 20) + " MB\n"
                + "Language: " + Languages.current() + ", theme: " + Themes.current() + "\n"
                + "Folder: " + new File(".").getAbsoluteFile().getParent() + "\n";
    }

    private static void section(JPanel panel, String title) {
        panel.add(Ui.title(title), "span, split 2, gaptop 8");
        panel.add(new JSeparator(), "growx, wrap");
    }

    private static void row(JPanel panel, String label, JComponent value) {
        JLabel l = new JLabel(label);
        l.setEnabled(false);
        panel.add(l, "top");
        panel.add(value, "wrap");
    }

    /** Text in the accent color that opens the address in the browser */
    private static JLabel link(String text, String url) {
        JLabel link = new JLabel("<html><u>" + text + "</u></html>");
        link.setForeground(Ui.accent(link));
        link.setToolTipText(url);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    boolean mail = url.startsWith("mailto:");
                    Desktop.Action action = mail ? Desktop.Action.MAIL : Desktop.Action.BROWSE;
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)) {
                        if (mail) {
                            Desktop.getDesktop().mail(new URI(url));
                        } else {
                            Desktop.getDesktop().browse(new URI(url));
                        }
                        return;
                    }
                } catch (Exception ex) {
                    log.info("Open " + url + ": " + ex.getMessage());
                }
                // no browser: the address goes to the clipboard
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(url.replace("mailto:", "")), null);
                link.setToolTipText(url + " (" + Labels.getLabel("gui.about.link.copied") + ")");
            }
        });
        link.setFont(link.getFont().deriveFont(Font.PLAIN));
        return link;
    }
}
