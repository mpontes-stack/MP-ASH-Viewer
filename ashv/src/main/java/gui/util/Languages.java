package gui.util;

import config.Labels;
import java.util.Locale;
import java.util.prefs.Preferences;
import lombok.extern.slf4j.Slf4j;

/**
 * Language of the texts, numbers and dates: English (default) or Portuguese. The last choice of the connection
 * dialog, otherwise the language of the system; applied when the app starts (before any window).
 */
@Slf4j
public final class Languages {
    public static final String EN = "en";
    public static final String PT = "pt";
    public static final String[] ALL = {EN, PT};

    private static final String LANGUAGE = "language";
    // own node: the "ASH-Viewer" node keeps the connection secrets
    private static final String PREFERENCES_NODE = "MP-ASH-Viewer";

    /** Locale of the system before the app changes it (CSV files follow the system, as Excel does) */
    private static final Locale SYSTEM = Locale.getDefault();
    private static String current = EN;

    private Languages() {}

    public static String current() {
        return current;
    }

    public static Locale systemLocale() {
        return SYSTEM;
    }

    /** Language of the application start */
    public static String startup() {
        String saved = null;
        try {
            saved = Preferences.userRoot().node(PREFERENCES_NODE).get(LANGUAGE, null);
        } catch (Exception e) {
            log.info("Language preference: " + e.getMessage());
        }
        return normalize(saved != null ? saved : SYSTEM.getLanguage());
    }

    /** Texts, number and date formats (date pickers too) of the language */
    public static void apply(String language) {
        current = normalize(language);
        Locale locale = PT.equals(current) ? new Locale("pt", "BR") : Locale.US;
        Locale.setDefault(locale);
        Labels.initialize(locale);
    }

    public static void save(String language) {
        try {
            Preferences.userRoot().node(PREFERENCES_NODE).put(LANGUAGE, normalize(language));
        } catch (Exception e) {
            log.info("Language preference: " + e.getMessage());
        }
    }

    private static String normalize(String language) {
        return language != null && language.trim().toLowerCase().startsWith(PT) ? PT : EN;
    }
}
