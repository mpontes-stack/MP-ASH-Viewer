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
    public enum Type {ACTIVITY, HISTORY, COMPARE, SESSIONS, RESOURCES, ADMIN, TOP_SQL, RAW, REPORT, LOCKS, DETAIL}

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
                case ADMIN: {
                    // gear
                    java.awt.geom.Area gear = new java.awt.geom.Area(new Ellipse2D.Double(3, 3, 10, 10));
                    for (int i = 0; i < 8; i++) {
                        java.awt.geom.AffineTransform r = java.awt.geom.AffineTransform.getRotateInstance(Math.PI / 4 * i, 8, 8);
                        gear.add(new java.awt.geom.Area(r.createTransformedShape(new Rectangle2D.Double(6.8, 1, 2.4, 3))));
                    }
                    gear.subtract(new java.awt.geom.Area(new Ellipse2D.Double(5.8, 5.8, 4.4, 4.4)));
                    g2.setColor(fill);
                    g2.fill(gear);
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(gear);
                    break;
                }
                case TOP_SQL: {
                    // ranking: bars from the longest
                    g2.setColor(color);
                    g2.fill(new Rectangle2D.Double(2, 2.5, 12, 2.6));
                    g2.fill(new Rectangle2D.Double(2, 6.7, 8.5, 2.6));
                    g2.fill(new Rectangle2D.Double(2, 10.9, 5, 2.6));
                    break;
                }
                case RAW: {
                    // table: header row and grid
                    g2.setColor(fill);
                    g2.fill(new Rectangle2D.Double(1.5, 2.5, 13, 3));
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(new Rectangle2D.Double(1.5, 2.5, 13, 11));
                    g2.draw(new Line2D.Double(1.5, 5.5, 14.5, 5.5));
                    g2.draw(new Line2D.Double(1.5, 9.5, 14.5, 9.5));
                    g2.draw(new Line2D.Double(6, 2.5, 6, 13.5));
                    break;
                }
                case REPORT: {
                    // document with text lines
                    Path2D page = new Path2D.Double();
                    page.moveTo(3, 1.5);
                    page.lineTo(10, 1.5);
                    page.lineTo(13, 4.5);
                    page.lineTo(13, 14.5);
                    page.lineTo(3, 14.5);
                    page.closePath();
                    g2.setColor(fill);
                    g2.fill(page);
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(page);
                    g2.draw(new Line2D.Double(5, 7, 11, 7));
                    g2.draw(new Line2D.Double(5, 9.5, 11, 9.5));
                    g2.draw(new Line2D.Double(5, 12, 9, 12));
                    break;
                }
                case LOCKS: {
                    // padlock
                    g2.setColor(color);
                    g2.draw(new Arc2D.Double(4.5, 1.5, 7, 8, 0, 180, Arc2D.OPEN));
                    g2.draw(new Line2D.Double(4.5, 5.5, 4.5, 7.5));
                    g2.draw(new Line2D.Double(11.5, 5.5, 11.5, 7.5));
                    g2.fill(new java.awt.geom.RoundRectangle2D.Double(3, 7.5, 10, 7, 2, 2));
                    break;
                }
                case DETAIL: {
                    // magnifier
                    g2.setColor(fill);
                    g2.fill(new Ellipse2D.Double(2, 2, 8.5, 8.5));
                    g2.setColor(color);
                    g2.draw(new Ellipse2D.Double(2, 2, 8.5, 8.5));
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(new Line2D.Double(9.5, 9.5, 14, 14));
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
            case ADMIN:
                return dark ? new Color(175, 180, 190) : new Color(95, 100, 110);
            case TOP_SQL:
                return dark ? new Color(240, 140, 90) : new Color(205, 90, 40);
            case RAW:
                return dark ? new Color(150, 170, 200) : new Color(80, 100, 140);
            case REPORT:
                return dark ? new Color(95, 160, 255) : new Color(25, 100, 210);
            case LOCKS:
                return dark ? new Color(230, 190, 70) : new Color(170, 120, 0);
            case DETAIL:
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
