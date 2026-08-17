plugins {
    id("java")
    id("maven-publish")
}
group = "fr.euphyllia.skyllia"

repositories {
    maven {
        name = "Luminol"
        url = uri("https://repo.menthamc.org/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")
    compileOnly("net.kyori:adventure-text-serializer-legacy:5.2.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("me.earthme.luminol:luminol-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly("dev.faststats.metrics:bukkit:0.27.0")
    compileOnly(project(":api"))
    compileOnly(project(":database"))
    compileOnly(project(":hook:worldedit"))
    compileOnly(project(":hook:fastasyncworldedit"))
    compileOnly(project(":hook:internalworld"))
    compileOnly(project(":hook:canvas"))
    compileOnly(project(":hook:luminol"))
    compileOnly(project(":hook:essentialsx"))
    compileOnly(project(":hook:luckperms"))
    compileOnly(project(":hook:quickshop"))
    compileOnly(project(":hook:cmi"))
    compileOnly(project(":hook:insights"))

    // NMS Version
    compileOnly(project(":nms:v1_20_R4"))
    compileOnly(project(":nms:v1_21_R1"))
    compileOnly(project(":nms:v1_21_R2"))
    compileOnly(project(":nms:v1_21_R3"))
    compileOnly(project(":nms:v1_21_R4"))
    compileOnly(project(":nms:v1_21_R5"))
    compileOnly(project(":nms:v1_21_R6"))
    compileOnly(project(":nms:v1_21_R7"))
    //compileOnly(project(":nms:v26_1"))
    //compileOnly(project(":nms:v26_2"))
}
java.disableAutoTargetJvm()

publishing {
    publications {
        create<MavenPublication>("gpr") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "Euphyllia-Repo"
            url = uri("https://repo.euphyllia.moe/repository/maven-releases/")
            credentials {
                username = System.getenv("NEXUS_USERNAME") ?: ""
                password = System.getenv("NEXUS_PASSWORD") ?: ""
            }
        }
    }
}
