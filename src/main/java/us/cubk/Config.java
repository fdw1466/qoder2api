package us.cubk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Config {

    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private static final Path configPath = Path.of("config.yaml");

    public record Qoder(List<String> pat, String basePrompt, boolean debug) {

        /** First PAT (or env QODER_PAT), for backwards compatibility with single-PAT callers. */
        public String firstPat() {
            if (pat != null && !pat.isEmpty()) return pat.get(0);
            return null;
        }
    }
    public record App(String host, int port, List<String> apiKeys) {}

    public static Qoder qoder() { return load().qoder(); }
    public static App app()    { return load().app(); }

    public static boolean debug() { return load().qoder().debug(); }

    private static volatile ConfigData INSTANCE;

    private static ConfigData load() {
        if (INSTANCE != null) return INSTANCE;
        synchronized (Config.class) {
            if (INSTANCE != null) return INSTANCE;
            INSTANCE = load0();
            return INSTANCE;
        }
    }

    @SuppressWarnings("unchecked")
    private static ConfigData load0() {
        if (!configPath.toFile().exists()) {
            return new ConfigData(
                new Qoder(patList(null), null, false),
                new App("127.0.0.1", 8080, List.of()));
        }
        try {
            Map<String, Object> yaml = yamlMapper.readValue(configPath.toFile(), Map.class);
            Map<String, Object> qoderMap = (Map<String, Object>) yaml.getOrDefault("qoder", Map.of());
            Map<String, Object> appMap   = (Map<String, Object>) yaml.getOrDefault("app",   Map.of());
            return new ConfigData(
                new Qoder(
                    patList(qoderMap.get("pat")),
                    str(qoderMap, "base_prompt"),
                    bool(qoderMap, "debug", false)),
                new App(
                    str(appMap, "host", "127.0.0.1"),
                    num(appMap, "port", 8080),
                    strList(appMap.get("api_keys"), "APP_API_KEYS")));
        } catch (IOException e) {
            System.err.println("[config] failed to load config.yaml: " + e.getMessage());
            return new ConfigData(
                new Qoder(patList(null), null, false),
                new App("127.0.0.1", 8080, List.of()));
        }
    }

    /**
     * Parse {@code qoder.pat} into a list. Accepts:
     * <ul>
     *   <li>a YAML list: {@code pat: ["a","b"]}</li>
     *   <li>a scalar string: {@code pat: "a"}</li>
     *   <li>env override {@code QODER_PAT}, comma-separated: {@code a,b,c}</li>
     * </ul>
     * Blank entries are dropped. Returns an empty list (not null) when nothing is configured.
     */
    @SuppressWarnings("unchecked")
    private static List<String> patList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            out.add(raw.toString().trim());
        }
        if (out.isEmpty()) {
            // env override: QODER_PAT may be "a" or "a,b,c"
            String env = System.getenv("QODER_PAT");
            if (env != null && !env.isBlank()) {
                for (String part : env.split(",")) {
                    if (!part.isBlank()) out.add(part.trim());
                }
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key) {
        return str(m, key, null);
    }
    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) {
            String env = System.getenv(key.toUpperCase().replace(".", "_").replace("-", "_"));
            if (env != null) return env;
            return def;
        }
        return v.toString();
    }
    private static int num(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }

    /**
     * Parse a string-list config field. Accepts a YAML list or a scalar string. If empty, falls back to
     * the given environment variable (comma-separated). Blank entries dropped.
     */
    private static List<String> strList(Object raw, String envName) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) out.add(o.toString().trim());
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            out.add(raw.toString().trim());
        }
        if (out.isEmpty()) {
            String env = System.getenv(envName);
            if (env != null && !env.isBlank()) {
                for (String part : env.split(",")) {
                    if (!part.isBlank()) out.add(part.trim());
                }
            }
        }
        return out;
    }

    private static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v == null) {
            String env = System.getenv(key.toUpperCase().replace(".", "_").replace("-", "_"));
            if (env != null) return env.equalsIgnoreCase("true") || env.equals("1") || env.equalsIgnoreCase("yes");
            return def;
        }
        if (v instanceof Boolean b) return b;
        String s = v.toString();
        return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
    }

    public record ConfigData(Qoder qoder, App app) {}
}
