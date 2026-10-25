package gui.util;

import config.Labels;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * CSV files that open in Excel (pt-BR): ";" separator, decimal comma, UTF-8 with BOM
 */
@Slf4j
public final class CsvExport {
    // as Excel: ";" where the decimal separator of the system is a comma, "," otherwise
    private static final DecimalFormatSymbols SYSTEM_SYMBOLS = DecimalFormatSymbols.getInstance(Languages.systemLocale());
    private static final String SEPARATOR = SYSTEM_SYMBOLS.getDecimalSeparator() == ',' ? ";" : ",";
    private static final String LAST_DIR = "export.lastDir";
    // own node: the "ASH-Viewer" node keeps the connection secrets
    private static final String PREFERENCES_NODE = "MP-ASH-Viewer";

    private CsvExport() {}

    /**
     * Suggested file name: "connection_tab_yyyyMMdd_HHmm-HHmm.csv"
     */
    public static String fileName(String connName, String tab, long begin, long end) {
        String period = new SimpleDateFormat("yyyyMMdd_HHmm").format(new Date(begin))
                + "-" + new SimpleDateFormat(sameDay(begin, end) ? "HHmm" : "yyyyMMdd_HHmm").format(new Date(end));
        String name = (connName == null || connName.isEmpty() ? "" : connName + "_") + tab + "_" + period;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".csv";
    }

    /**
     * Ask where to save and write the rows; shows the result (or the error) to the user
     */
    public static void save(Component parent, String suggestedName, List<String> header, List<Object[]> rows) {
        JFileChooser chooser = new JFileChooser(lastDir());
        chooser.setDialogTitle(Labels.getLabel("gui.export.title"));
        chooser.setFileFilter(new FileNameExtensionFilter("CSV (Excel)", "csv"));
        chooser.setSelectedFile(new File(lastDir(), suggestedName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File file = chooser.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith(".csv")) {
            file = new File(file.getParentFile(), file.getName() + ".csv");
        }
        if (file.exists() && JOptionPane.showConfirmDialog(parent,
                Labels.getLabel("gui.export.overwrite") + "\n" + file.getName(), Labels.getLabel("gui.export.title"),
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        setLastDir(file.getParentFile());

        try {
            write(file, header, rows);
            JOptionPane.showMessageDialog(parent, rows.size() + " " + Labels.getLabel("gui.export.done") + "\n" + file.getPath(),
                    Labels.getLabel("gui.export.title"), JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            log.error("CSV export", e);
            JOptionPane.showMessageDialog(parent, Labels.getLabel("gui.export.error") + "\n" + e.getMessage(),
                    Labels.getLabel("gui.export.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void write(File file, List<String> header, List<Object[]> rows) throws IOException {
        DecimalFormat decimal = new DecimalFormat("0.##########", SYSTEM_SYMBOLS);
        try (Writer out = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            out.write('﻿'); // BOM: Excel reads the file as UTF-8
            writeLine(out, header.toArray(), decimal);
            for (Object[] row : rows) {
                writeLine(out, row, decimal);
            }
        }
    }

    private static void writeLine(Writer out, Object[] values, DecimalFormat decimal) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(SEPARATOR);
            }
            sb.append(cell(values[i], decimal));
        }
        sb.append("\r\n");
        out.write(sb.toString());
    }

    private static String cell(Object value, DecimalFormat decimal) {
        if (value == null) {
            return "";
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            return decimal.format(value);
        }
        String text = String.valueOf(value);
        if (text.contains(SEPARATOR) || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static boolean sameDay(long begin, long end) {
        SimpleDateFormat day = new SimpleDateFormat("yyyyMMdd");
        return day.format(new Date(begin)).equals(day.format(new Date(end)));
    }

    private static File lastDir() {
        try {
            String dir = Preferences.userRoot().node(PREFERENCES_NODE).get(LAST_DIR, null);
            if (dir != null && new File(dir).isDirectory()) {
                return new File(dir);
            }
        } catch (Exception e) {
            log.info("Export folder preference: " + e.getMessage());
        }
        return new File(System.getProperty("user.home"));
    }

    private static void setLastDir(File dir) {
        try {
            if (dir != null) {
                Preferences.userRoot().node(PREFERENCES_NODE).put(LAST_DIR, dir.getPath());
            }
        } catch (Exception e) {
            log.info("Export folder preference: " + e.getMessage());
        }
    }
}
