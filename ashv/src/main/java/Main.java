import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import config.dagger.DaggerMainComponent;
import config.dagger.MainComponent;
import gui.MainWindow;
import javax.swing.UIManager;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Main {
    public static void main(String... args) {
        System.getProperties().setProperty("oracle.jdbc.J2EE13Compliant", "true");

        log.info("Start application");

        setupLookAndFeel();

        MainComponent mainComponent = DaggerMainComponent.create();

        // create the main window using dependency injection
        MainWindow mainWindow = mainComponent.createMainWindow();
    }

    /**
     * Theme selection via -Dashv.theme=light (default) | dark | classic
     */
    private static void setupLookAndFeel() {
        String theme = System.getProperty("ashv.theme", "light").toLowerCase();

        try {
            switch (theme) {
                case "classic":
                    return;
                case "dark":
                    FlatDarkLaf.setup();
                    break;
                default:
                    FlatLightLaf.setup();
            }

            UIManager.put("Component.arc", 6);
            UIManager.put("Button.arc", 6);
            UIManager.put("TextComponent.arc", 6);
            UIManager.put("ScrollBar.showButtons", false);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("Table.showHorizontalLines", true);
            UIManager.put("Tree.showDefaultIcons", true);
        } catch (Exception e) {
            log.warn("Unable to set FlatLaf look and feel, using default: " + e.getMessage());
        }
    }
}
