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
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import javax.swing.Icon;

/**
 * Small icons of the main tabs, drawn (sharp at any scale) with light / dark theme colors
 */
public class TabIcon implements Icon {
    public enum Type {ACTIVITY, HISTORY, COMPARE, SESSIONS, RESOURCES}

    private static final int SIZE = 16;

    private final Type type;

    public TabIcon(Type type) {
        this.type = type;
    }

    @Override
    public void paintIcon(Component c, Graphics g, int x, int y) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.translate(x, y);
            Color color = getColor();
            Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 70);
            g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (type) {
                case ACTIVITY: {
                    // active sessions over time: an area with peaks
                    Path2D line = new Path2D.Double();
                    line.moveTo(1.5, 11);
                    line.lineTo(4, 8);
                    line.lineTo(6.5, 10);
                    line.lineTo(9, 3.5);
                    line.lineTo(11.5, 8.5);
                    line.lineTo(14.5, 6);
                    Path2D area = new Path2D.Double(line);
                    area.lineTo(14.5, 14);
                    area.lineTo(1.5, 14);
                    area.closePath();
                    g2.setColor(fill);
                    g2.fill(area);
                    g2.setColor(color);
                    g2.draw(line);
                    g2.draw(new Line2D.Double(1.5, 14, 14.5, 14));
                    break;
                }
                case HISTORY: {
                    // clock
                    g2.setColor(fill);
                    g2.fill(new Ellipse2D.Double(1.5, 1.5, 13, 13));
                    g2.setColor(color);
                    g2.draw(new Ellipse2D.Double(1.5, 1.5, 13, 13));
                    g2.draw(new Line2D.Double(8, 8, 8, 4));
                    g2.draw(new Line2D.Double(8, 8, 11, 9.5));
                    break;
                }
                case COMPARE: {
                    // period A (outline) and period B (filled) side by side
                    g2.setColor(color);
                    g2.draw(new Rectangle2D.Double(2.5, 6.5, 4.5, 7.5));
                    g2.fill(new Rectangle2D.Double(9, 2.5, 5, 12));
                    g2.draw(new Line2D.Double(1, 14.5, 15, 14.5));
                    break;
                }
                case SESSIONS: {
                    // two people
                    g2.setColor(fill);
                    g2.fill(new Arc2D.Double(6.5, 9, 9, 9, 0, 180, Arc2D.CHORD));
                    g2.setColor(color);
                    g2.draw(new Ellipse2D.Double(3, 2.5, 4.5, 4.5));
                    g2.draw(new Arc2D.Double(0.8, 8.5, 9, 9, 0, 180, Arc2D.OPEN));
                    g2.draw(new Ellipse2D.Double(8.8, 4, 4.4, 4.4));
                    g2.draw(new Arc2D.Double(6.5, 9.5, 9, 9, 0, 180, Arc2D.OPEN));
                    break;
                }
                case RESOURCES: {
                    // gauge
                    g2.setColor(fill);
                    g2.fill(new Arc2D.Double(1.5, 3, 13, 13, 0, 180, Arc2D.PIE));
                    g2.setColor(color);
                    g2.draw(new Arc2D.Double(1.5, 3, 13, 13, 0, 180, Arc2D.OPEN));
                    g2.draw(new Line2D.Double(1.5, 9.5, 14.5, 9.5));
                    g2.draw(new Line2D.Double(8, 9.5, 11.5, 5));
                    g2.fill(new Ellipse2D.Double(6.5, 8, 3, 3));
                    break;
                }
                default:
            }
        } finally {
            g2.dispose();
        }
    }

    private Color getColor() {
        boolean dark;
        try {
            dark = FlatLaf.isLafDark();
        } catch (Throwable e) {
            dark = false;
        }
        switch (type) {
            case ACTIVITY:
                return dark ? new Color(90, 200, 110) : new Color(30, 140, 60);
            case HISTORY:
                return dark ? new Color(95, 160, 255) : new Color(25, 100, 210);
            case COMPARE:
                return dark ? new Color(240, 170, 60) : new Color(200, 120, 0);
            case SESSIONS:
                return dark ? new Color(185, 140, 240) : new Color(120, 70, 190);
            default:
                return dark ? new Color(80, 200, 200) : new Color(0, 130, 140);
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
