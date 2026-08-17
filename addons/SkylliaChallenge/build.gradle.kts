plugins {
    id("java")
}

group = "fr.euphyllia.skylliachallenge";

repositories {
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.oraxen.com/releases")
    maven("https://repo.nexomc.com/releases")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    compileOnly(project(":api"))
    compileOnly(project(":plugin"))
    compileOnly(project(":database"))
    compileOnly(project(":addons:SkylliaBank"))

    implementation("dev.triumphteam:triumph-gui-paper:3.1.13")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    compileOnly("io.th0rgal:oraxen:1.180.0")
    compileOnly("com.nexomc:nexo:1.16.1")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}
