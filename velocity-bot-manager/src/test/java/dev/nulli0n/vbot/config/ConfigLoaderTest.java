package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {
    @Test
    void parsesMinimalBotAndAppliesDefaults() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
            """);

        assertThat(config.proxy().address()).isEqualTo("127.0.0.1");
        assertThat(config.bots()).containsKey("farm01");
        assertThat(config.bots().get("farm01").auth().mode()).isEqualTo(BotPluginConfig.AuthMode.AUTO);
        assertThat(config.bots().get("farm01").enabled()).isFalse();
    }

    @Test
    void rejectsInvalidOfflineUsername() {
        assertThatThrownBy(() -> parse("""
            bots:
              bad:
                username: name-with-dash
                password: secret
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("username");
    }

    @Test
    void allowsPasswordlessBotWhenAuthenticationIsDisabled() {
        BotPluginConfig config = parse("""
            bots:
              NoAuth:
                username: AFK_NoAuth
                auth:
                  mode: NONE
            """);

        assertThat(config.bots().get("noauth").auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
    }

    private static BotPluginConfig parse(String yaml) {
        return ConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }
}
