package core.update;

import gui.custom.AboutDialog;
import gui.custom.AppLogo;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Latest release of the project on GitHub, asked only when the user clicks "Check for updates" (the app never
 * calls the internet by itself). Nothing is downloaded: the user gets the version, its notes and the release page.
 */
@Slf4j
public final class UpdateCheck {
    private static final String LATEST = "https://api.github.com/repos/mpontes-stack/MP-ASH-Viewer/releases/latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_BODY = 512 * 1024;
    private static final int MAX_NOTES = 4000;
    /** Tags are "v1.5.0": anything else from the answer is not used to build the page address */
    private static final Pattern TAG = Pattern.compile("^v?\\d{1,4}(\\.\\d{1,4}){0,3}$");

    private UpdateCheck() {
    }

    /** Answer of a check */
    public static final class Result {
        /** e.g. "1.6.0", null when the check failed */
        public final String latest;
        public final boolean newer;
        /** Release notes as written on GitHub (Markdown, plain text), may be empty */
        public final String notes;
        /** Page of the release (built here from the tag, never taken from the answer) */
        public final String page;
        /** Why the check failed, null when it worked */
        public final String error;

        private Result(String latest, boolean newer, String notes, String page, String error) {
            this.latest = latest;
            this.newer = newer;
            this.notes = notes;
            this.page = page;
            this.error = error;
        }

        static Result failed(String error) {
            return new Result(null, false, "", AboutDialog.PROJECT_URL + "/releases", error);
        }
    }

    /** Ask GitHub (call off the EDT; at most about 10 s). Uses the system proxy when Java is set to use it */
    public static Result check() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.getDefault())
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(LATEST))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", AppLogo.NAME + "/" + AppLogo.VERSION)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Result.failed("HTTP " + response.statusCode());
            }
            return parse(response.body(), AppLogo.VERSION);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed(e.toString());
        } catch (Exception e) {
            log.info("Update check: " + e);
            return Result.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** The JSON of the GitHub API (read with SnakeYAML's safe loader: JSON is YAML) */
    static Result parse(String json, String current) {
        if (json == null || json.length() > MAX_BODY) {
            return Result.failed("unexpected answer");
        }
        Object data = new Yaml(new SafeConstructor(new LoaderOptions())).load(json);
        if (!(data instanceof Map)) {
            return Result.failed("unexpected answer");
        }
        Map<?, ?> release = (Map<?, ?>) data;
        Object tag = release.get("tag_name");
        if (!(tag instanceof String) || !TAG.matcher((String) tag).matches()) {
            return Result.failed("unexpected version: " + tag);
        }
        String latest = ((String) tag).startsWith("v") ? ((String) tag).substring(1) : (String) tag;
        Object body = release.get("body");
        String notes = body instanceof String ? ((String) body).replace("\r", "").trim() : "";
        if (notes.length() > MAX_NOTES) {
            notes = notes.substring(0, MAX_NOTES) + "\n...";
        }
        return new Result(latest, compare(latest, current) > 0, notes,
                AboutDialog.PROJECT_URL + "/releases/tag/" + tag, null);
    }

    /** "1.10.0" > "1.9.2"; missing parts are 0 */
    static int compare(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int dx = i < x.length ? number(x[i]) : 0;
            int dy = i < y.length ? number(y[i]) : 0;
            if (dx != dy) {
                return Integer.compare(dx, dy);
            }
        }
        return 0;
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
