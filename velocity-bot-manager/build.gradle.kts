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
    maven("https://repo.opencollab.dev/main/")
    maven("https://jitpack.io")
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
        // Service providers from Adventure and Cloudburst must remain visible to
        // the transformer even though duplicate classes/resources are excluded.
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        mergeServiceFiles()

        // Keep the embedded protocol client isolated from other Velocity plugins.
        relocate("org.geysermc.mcprotocollib", "dev.nulli0n.vbot.lib.mcprotocollib")
        relocate("org.cloudburstmc", "dev.nulli0n.vbot.lib.cloudburstmc")
        relocate("io.netty", "dev.nulli0n.vbot.lib.netty")
        relocate("it.unimi.dsi.fastutil", "dev.nulli0n.vbot.lib.fastutil")
        relocate("com.google.gson", "dev.nulli0n.vbot.lib.gson")
        relocate("net.raphimc", "dev.nulli0n.vbot.lib.raphimc")
        relocate("net.lenni0451", "dev.nulli0n.vbot.lib.lenni0451")
        relocate("org.yaml.snakeyaml", "dev.nulli0n.vbot.lib.snakeyaml")
    }

    build {
        dependsOn(shadowJar)
    }
}

val verifyShadowJar by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Loads the relocated protocol client from the single deployable JAR."
    dependsOn(tasks.shadowJar)
    classpath = files(tasks.shadowJar.flatMap { it.archiveFile })
    mainClass.set("dev.nulli0n.vbot.verify.ProtocolSmokeMain")
}

tasks.check {
    dependsOn(verifyShadowJar)
}
