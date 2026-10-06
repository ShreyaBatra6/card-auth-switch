package com.cardswitch.loadgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code --name value} command-line options; a name without a value is a boolean flag.
 */
final class Options {

    private final Map<String, String> values = new HashMap<>();

    static Options parse(String[] args, int from) {
        Options options = new Options();
        for (int i = from; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            }
            String name = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                options.values.put(name, args[++i]);
            } else {
                options.values.put(name, "true");
            }
        }
        return options;
    }

    void rejectUnknown(Set<String> known) {
        for (String name : values.keySet()) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("Unknown option --" + name);
            }
        }
    }

    String string(String name, String defaultValue) {
        return values.getOrDefault(name, defaultValue);
    }

    int integer(String name, int defaultValue) {
        return values.containsKey(name) ? Integer.parseInt(values.get(name)) : defaultValue;
    }

    double decimal(String name, double defaultValue) {
        return values.containsKey(name) ? Double.parseDouble(values.get(name)) : defaultValue;
    }

    boolean flag(String name) {
        return Boolean.parseBoolean(values.getOrDefault(name, "false"));
    }

    List<String> list(String name, String defaultValue) {
        String raw = values.getOrDefault(name, defaultValue);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(items::add);
        return items;
    }
}
