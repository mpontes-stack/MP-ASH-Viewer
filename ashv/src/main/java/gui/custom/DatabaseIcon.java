package gui.custom;

import com.formdev.flatlaf.FlatLaf;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import javax.swing.Icon;
import javax.swing.UIManager;

/**
 * Database (cylinder) icon colored by database type: Oracle, Postgres or neutral
 */
public class DatabaseIcon implements Icon {
    public enum Type {ORACLE, POSTGRES, OTHER}

    private static final int SIZE = 16;

    private final Type type;

    public DatabaseIcon(Type type) {
        this.type = type;
    }

    /**
     * @param profileName connection profile, e.g. OracleSE, OracleEE, Postgres96
     */
    public static Type typeOf(String profileName) {
        String p = profileName == null ? "" : profileName.toLowerCase();
        if (p.startsWith("oracle")) {
            return Type.ORACLE;
        } else if (p.startsWith("postgres")) {
            return Type.POSTGRES;
        }
        return Type.OTHER;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);

            Color color = getColor();
            double left = 3.5, right = 12.5, width = right - left, h = 5.2;
            double top = 1.5, bottom = 9.5;

            // body fill
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
            g2.fill(new Rectangle2D.Double(left, top + h / 2, width, bottom - top));
            g2.fill(new Ellipse2D.Double(left, bottom, width, h));
            g2.fill(new Ellipse2D.Double(left, top, width, h));

            // outline
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Ellipse2D.Double(left, top, width, h));                               // top
            g2.draw(new Arc2D.Double(left, (top + bottom) / 2, width, h, 180, 180, Arc2D.OPEN)); // middle
            g2.draw(new Arc2D.Double(left, bottom, width, h, 180, 180, Arc2D.OPEN));          // bottom
            g2.draw(new Line2D.Double(left, top + h / 2, left, bottom + h / 2));              // sides
            g2.draw(new Line2D.Double(right, top + h / 2, right, bottom + h / 2));
        } finally {
            g2.dispose();
        }
    }

    private Color getColor() {
        boolean dark = FlatLaf.isLafDark();
        switch (type) {
            case ORACLE:
                return dark ? new Color(235, 105, 90) : new Color(199, 70, 52);
            case POSTGRES:
                return dark ? new Color(110, 160, 210) : new Color(51, 103, 145);
            default:
                Color color = UIManager.getColor("Tree.icon.leafColor");
                return color != null ? color : new Color(110, 110, 110);
        }
    }

    @Override
    public int getIconWidth() {
        return SIZE;
    }

    @Override
    public int getIconHeight() {
        return SIZE;
    }
}
