import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import config.dagger.DaggerMainComponent;
import config.dagger.MainComponent;
import gui.MainWindow;
import java.awt.Color;
import javax.swing.UIManager;
import lombok.extern.slf4j.Slf4j;
import org.rtv.Options;

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
                    lighterDarkWaitClassColors();
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

    /**
     * Configuration, Administrative (and Postgres Timeout) are dark brown / olive: almost invisible on the dark background
     */
    private static void lighterDarkWaitClassColors() {
        Options.getInstance();
        Color configuration = new Color(145, 95, 50);
        Color administrative = new Color(150, 150, 40);
        Options.getOracleMainColor().put(Options.LBL_CONFIGURATION, configuration);
        Options.getOracleMainColor().put(Options.LBL_ADMINISTRATIVE, administrative);
        Options.getPgMainColor().put(Options.LBL_PG_TIMEOUT, configuration);
        Options.setColor(Options.LBL_CONFIGURATION, configuration);
        Options.setColor(Options.LBL_ADMINISTRATIVE, administrative);
        Options.setColor(Options.LBL_PG_TIMEOUT, configuration);
    }
}
