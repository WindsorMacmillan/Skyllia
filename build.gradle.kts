plugins {
    id("java-library")
    id("java")
    id("maven-publish")
    id("io.github.goooler.shadow") version "8.1.8"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.modrinth.minotaur") version "2.9.0"
}

evaluationDependsOn(":plugin")

java.disableAutoTargetJvm()

val paperRepo = "https://repo.papermc.io/repository/maven-public/";
val sonatypeRepo = "https://oss.sonatype.org/content/groups/public/";
val engineHubRepo = "https://maven.enginehub.org/repo/";
val jitpack = "https://jitpack.io"
val mojang = "https://libraries.minecraft.net";
var extendedclip = "https://repo.extendedclip.com/content/repositories/placeholderapi/";
var theNextLvl = "https://repo.thenextlvl.net/releases";
var euphyRepo = "https://repo.euphyllia.moe/repository/maven-public/"

dependencies {
    implementation(project(":database"))
    implementation(project(":api"))
    implementation(project(":plugin"))
    implementation(project(":hook:worldedit"))
    implementation(project(":hook:fastasyncworldedit"))
    implementation(project(":hook:internalworld"))
    implementation(project(":hook:canvas"))
    implementation(project(":hook:luminol"))
    implementation(project(":hook:essentialsx"))
    implementation(project(":hook:luckperms"))
    implementation(project(":hook:quickshop"))
    implementation(project(":hook:cmi"))
    implementation(project(":hook:insights"))

    // NMS Version
    implementation(project(":nms:v1_20_R4"))
    implementation(project(":nms:v1_21_R1"))
    implementation(project(":nms:v1_21_R2"))
    implementation(project(":nms:v1_21_R3"))
    implementation(project(":nms:v1_21_R4"))
    implementation(project(":nms:v1_21_R5"))
    implementation(project(":nms:v1_21_R6"))
    implementation(project(":nms:v1_21_R7"))
    implementation(project(":nms:v26_1"))
    implementation(project(":nms:v26_2"))
    implementation(project(":nms:v26_3"))
}

allprojects {
    version = "3.0-" + (System.getenv("GITHUB_RUN_NUMBER") ?: getGitCommitHash())
    description = "Plugin Skyblock on Folia";

    apply(plugin = "java-library")
    apply(plugin = "io.github.goooler.shadow")
    apply(plugin = "maven-publish")

    java {
        toolchain {
            // 本服务端项目自定义修改：以下模块需以 JVM 25 编译：
            //  1) plugin 与 hook:luminol 直接引用 me.earthme.luminol.* 类型，而取代
            //     已失效 luminol-api 的 fun.bm.lophine:lophine-api 以 JVM 25（major 69）构建；
            //  2) 各 addons 通过 compileOnly(project(":plugin")) 消费 :plugin 的类文件，
            //     而 javac 21 无法读取 major 69 的类文件（"类文件具有错误的版本 69.0, 应为 65.0"），
            //     故必须同样以 25 编译；否则整套构建无法通过。
            // 其余模块（api / database / nms 1.21.x 等）维持 21，与上游保持一致。
            // 注意：:plugin 被上方 evaluationDependsOn 提前求值，其模块内 toolchain 会被本块覆盖，
            // 因此必须在此处统一指定（不可仅在模块内设置）。
            val needsJava25 = name == "plugin" || name == "luminol" || path.startsWith(":addons")
            languageVersion.set(JavaLanguageVersion.of(if (needsJava25) 25 else 21))
        }
    }

    repositories {
        mavenLocal()
        mavenCentral()
        maven(paperRepo)
        maven(sonatypeRepo)
        maven(engineHubRepo)
        maven(mojang)
        //maven(jitpack)
        maven(extendedclip)
        maven(theNextLvl)
        maven(euphyRepo)
    }

    dependencies {
        compileOnly("org.apache.maven.resolver:maven-resolver-api:2.0.18")
        compileOnly("org.apache.logging.log4j:log4j-api:2.26.0")
        compileOnly("org.apache.logging.log4j:log4j-core:2.26.0")
        compileOnly("org.mariadb.jdbc:mariadb-java-client:3.5.9")
        compileOnly("com.zaxxer:HikariCP:7.1.0")
        compileOnly("org.xerial:sqlite-jdbc:3.51.2.0")
        compileOnly("org.jetbrains:annotations:26.1.0")
        compileOnly("org.jspecify:jspecify:1.0.1")
        compileOnly("com.electronwill.night-config:toml:3.8.3")
        compileOnly("net.md-5:bungeecord-api:1.20-R0.2")
        compileOnly("com.mojang:brigadier:1.0.18")
    }

    tasks {

        compileJava {
            options.encoding = "UTF-8"
        }
        processResources {
            filesMatching("**/paper-plugin.yml") {
                expand(rootProject.project.properties)
            }

            // Always re-run this task
            outputs.upToDateWhen { false }
        }

    }
}

tasks.test {
    useJUnitPlatform()
}



fun getGitCommitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readText().trim().ifBlank { "nogit" }
    } catch (e: Exception) {
        "nogit"
    }
}



runPaper.folia.registerTask()

tasks {
    runServer {
        minecraftVersion("1.21.8")
    }
}

modrinth {
    token.set(System.getenv("MODRINTH_TOKEN"))

    projectId.set("skyllia")

    versionNumber.set(project.version.toString())
    versionName.set("Skyllia ${project.version}")

    changelog.set(
        System.getenv("commit_msg")
            ?: "Automatic build from GitHub Actions."
    )

    uploadFile.set(tasks.named("shadowJar"))

    debugMode.set(false)

    additionalFiles.set(
        listOf(
            project(":addons:SkylliaOre").tasks.named<Jar>("shadowJar"),
            //project(":addons:SkylliaInsights").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaChat").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaBank").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaChallenge").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaChest").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaAcidRain").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaIslandValue").tasks.named<Jar>("shadowJar"),
            project(":addons:SkylliaBackup").tasks.named<Jar>("shadowJar"),
//            project(":addons:SkylliaExtra").tasks.named<Jar>("shadowJar"),
        )
    )

    gameVersions.addAll(
        "1.20.6",
        "1.21",
        "1.21.1",
        "1.21.2",
        "1.21.3",
        "1.21.4",
        "1.21.5",
        "1.21.6",
        "1.21.7",
        "1.21.8",
        "1.21.9",
        "1.21.10",
        "1.21.11",
        "26.1",
        "26.1.1",
        "26.1.2",
        "26.2"
    )

    loaders.addAll("folia", "paper", "purpur")

    versionType.set("release")

//    dependencies {
//        optional.version("essentialsx")
//        optional.version("placeholderapi")
//        optional.version("essentialsx-spawn")
//        optional.version("worldedit")
//        optional.version("fastasyncworldedit")
//    }
}

tasks.modrinth {
    dependsOn(
        "shadowJar",
        ":addons:SkylliaOre:shadowJar",
        //":addons:SkylliaInsights:shadowJar",
        ":addons:SkylliaChat:shadowJar",
        ":addons:SkylliaBank:shadowJar",
        ":addons:SkylliaChallenge:shadowJar",
        ":addons:SkylliaChest:shadowJar",
        ":addons:SkylliaAcidRain:shadowJar",
        ":addons:SkylliaIslandValue:shadowJar",
        ":addons:SkylliaBackup:shadowJar",
//        ":addons:SkylliaExtra:shadowJar"
    )
}
