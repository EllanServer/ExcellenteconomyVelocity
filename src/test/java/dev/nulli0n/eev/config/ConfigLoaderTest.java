package dev.nulli0n.eev.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigLoaderTest {
    @TempDir
    Path directory;

    @Test
    void suppliesNewDefaultsWithoutOverwritingExistingMessages() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
            default-currency: money
            database:
              jdbc-url: jdbc:mysql://unused/test
            currencies:
              money:
                column: money
                pay-permission: legacy.currency.money
            messages:
              no-permission: custom denial
            """);

        PluginConfig config = ConfigLoader.load(directory);

        assertThat(config.messages().get("no-permission")).isEqualTo("custom denial");
        assertThat(config.messages()).containsKey("reload-success");
        assertThat(config.permissions().give()).isEqualTo("excellenteconomyvelocity.command.currency.add");
        assertThat(config.currency("money").orElseThrow().permissionRequired()).isTrue();
        assertThat(config.currency("money").orElseThrow().permission()).isEqualTo("legacy.currency.money");
    }
}
