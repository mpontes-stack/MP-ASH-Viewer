package gui.custom;

import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Font;
import java.awt.Image;
import java.awt.image.BaseMultiResolutionImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * MP-ASH-Viewer logo: monkey image (1x / 2x for HiDPI) with the name in the interface font
 */
@Slf4j
public final class AppLogo {
    public static final String NAME = "MP-ASH-Viewer";

    private static final Color BROWN = new Color(92, 58, 30);       // logo text color
    private static final Color BROWN_DARK_THEME = new Color(214, 170, 120);

    private AppLogo() {}

    /**
     * Logo label
     *
     * @param iconHeight monkey height (28 or 40, images prepared for these sizes)
     * @param fontDelta  font size relative to the interface font
     */
    public static JLabel label(int iconHeight, float fontDelta) {
        JLabel label = new JLabel(NAME);
        Image image = monkey(iconHeight);
        if (image != null) {
            label.setIcon(new ImageIcon(image));
        }
        label.setIconTextGap(Math.max(4, iconHeight / 5));
        label.setHorizontalTextPosition(SwingConstants.RIGHT);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize2D() + fontDelta));
        label.setForeground(isDark() ? BROWN_DARK_THEME : BROWN);
        label.setToolTipText(NAME);
        return label;
    }

    /** Monkey image for 1x and 2x screens */
    public static Image monkey(int height) {
        Image base = read("/logo/monkey-" + height + ".png");
        Image hiDpi = read("/logo/monkey-" + (height * 2) + ".png");
        if (base == null) {
            return null;
        }
        return hiDpi == null ? base : new BaseMultiResolutionImage(base, hiDpi);
    }

    /** Window / taskbar icons */
    public static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>();
        for (int size : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            Image icon = read("/logo/icon-" + size + ".png");
            if (icon != null) {
                icons.add(icon);
            }
        }
        return icons;
    }

    private static Image read(String resource) {
        try (InputStream in = AppLogo.class.getResourceAsStream(resource)) {
            return in == null ? null : ImageIO.read(in);
        } catch (Exception e) {
            log.info("Logo resource " + resource + ": " + e.getMessage());
            return null;
        }
    }

    private static boolean isDark() {
        try {
            return FlatLaf.isLafDark();
        } catch (Throwable e) {
            return false;
        }
    }
}
