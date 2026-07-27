plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.nulli0n.velocitybotmanager"
version = providers.gradleProperty("pluginVersion").orElse("0.1.0-SNAPSHOT").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-releases/")
    maven("https://repo.opencollab.dev/maven-snapshots/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0")

    implementation("org.geysermc.mcprotocollib:protocol:1.21.11-SNAPSHOT")
    implementation("org.yaml:snakeyaml:2.5")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveBaseName.set("VelocityBotManager")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        mergeServiceFiles()

        // Keep the embedded protocol client isolated from other Velocity plugins.
        relocate("org.geysermc.mcprotocollib", "dev.nulli0n.vbot.lib.mcprotocollib")
        relocate("org.cloudburstmc", "dev.nulli0n.vbot.lib.cloudburstmc")
        relocate("org.yaml.snakeyaml", "dev.nulli0n.vbot.lib.snakeyaml")
    }

    build {
        dependsOn(shadowJar)
    }
}
