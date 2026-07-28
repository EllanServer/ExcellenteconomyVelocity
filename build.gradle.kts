plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.nulli0n.excellenteconomyvelocity"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

repositories {
    maven("https://maven.aliyun.com/repository/central")
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:4.0.0")
    annotationProcessor("com.velocitypowered:velocity-api:4.0.0")

    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("com.mysql:mysql-connector-j:9.7.0")
    implementation("io.lettuce:lettuce-core:7.6.0.RELEASE")
    implementation("org.yaml:snakeyaml:2.5")
    implementation("com.google.code.gson:gson:2.13.2")

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

    processResources {
        filteringCharset = "UTF-8"
        filesMatching("config.yml") {
            expand("version" to project.version)
        }
    }

    test {
        useJUnitPlatform()
    }

    shadowJar {
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        relocate("com.zaxxer.hikari", "dev.nulli0n.eev.lib.hikari")
        relocate("io.lettuce", "dev.nulli0n.eev.lib.lettuce")
        relocate("org.yaml.snakeyaml", "dev.nulli0n.eev.lib.snakeyaml")
        relocate("com.google.gson", "dev.nulli0n.eev.lib.gson")
    }

    build {
        dependsOn(shadowJar)
    }
}
