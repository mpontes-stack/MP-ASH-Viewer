package core.filter;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Named filter combinations stored in filters.yaml next to the application (shared by all connections)
 */
@Slf4j
@Singleton
public class FilterPresets {
    private final Path file = Paths.get(".").toAbsolutePath().normalize().resolve("filters.yaml");

    private final TreeMap<String, Map<String, Set<String>>> presets = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    @Inject
    public FilterPresets() {
        load();
    }

    public synchronized Map<String, Map<String, Set<String>>> getAll() {
        return new TreeMap<>(presets);
    }

    public synchronized boolean contains(String name) {
        return presets.containsKey(name);
    }

    public synchronized void save(String name, Map<String, Set<String>> conditions) {
        presets.put(name, copy(conditions));
        store();
    }

    public synchronized void delete(String name) {
        presets.remove(name);
        store();
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object data = new Yaml().load(reader);
            if (!(data instanceof Map)) {
                return;
            }
            ((Map<Object, Object>) data).forEach((name, conditions) -> {
                if (!(conditions instanceof Map)) {
                    return;
                }
                Map<String, Set<String>> out = new LinkedHashMap<>();
                ((Map<Object, Object>) conditions).forEach((col, values) -> {
                    Set<String> set = new LinkedHashSet<>();
                    if (values instanceof List) {
                        ((List<Object>) values).forEach(v -> set.add(v == null ? "" : String.valueOf(v)));
                    } else if (values != null) {
                        set.add(String.valueOf(values));
                    }
                    if (!set.isEmpty()) {
                        out.put(String.valueOf(col), set);
                    }
                });
                if (!out.isEmpty()) {
                    presets.put(String.valueOf(name), out);
                }
            });
        } catch (Exception e) {
            log.error("Unable to read " + file + ": " + e.getMessage());
        }
    }

    private void store() {
        Map<String, Map<String, List<String>>> data = new LinkedHashMap<>();
        presets.forEach((name, conditions) -> {
            Map<String, List<String>> c = new LinkedHashMap<>();
            conditions.forEach((col, values) -> c.put(col, new java.util.ArrayList<>(values)));
            data.put(name, c);
        });

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            new Yaml(options).dump(data, writer);
        } catch (IOException e) {
            log.error("Unable to write " + file + ": " + e.getMessage());
        }
    }

    private static Map<String, Set<String>> copy(Map<String, Set<String>> conditions) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        conditions.forEach((k, v) -> out.put(k, new LinkedHashSet<>(v)));
        return out;
    }
}
