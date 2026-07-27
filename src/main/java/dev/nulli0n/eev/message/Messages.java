package dev.nulli0n.eev.message;

import dev.nulli0n.eev.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Messages {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, String> templates;
    private final String prefix;

    public Messages(PluginConfig config) {
        this.templates = config.messages();
        this.prefix = templates.getOrDefault("prefix", "");
    }

    public Component get(String key) {
        return get(key, Map.of());
    }

    public Component get(String key, Map<String, ?> replacements) {
        String template = templates.getOrDefault(key, "<red>Missing message: " + key);
        Map<String, Object> safe = new LinkedHashMap<>(replacements);
        for (Map.Entry<String, Object> entry : safe.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", escape(String.valueOf(entry.getValue())));
        }
        return miniMessage.deserialize(prefix + template);
    }

    private static String escape(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("<", "\\<")
            .replace(">", "\\>");
    }
}
