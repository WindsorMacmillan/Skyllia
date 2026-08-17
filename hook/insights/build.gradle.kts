plugins {
    id("java")
}

group = "fr.euphyllia.skyllia.hook.insights"

repositories {
    maven("https://repo.jsinco.dev/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")
    compileOnly(project(":api"))
    compileOnly("dev.frankheijden.insights:Insights:6.22.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}