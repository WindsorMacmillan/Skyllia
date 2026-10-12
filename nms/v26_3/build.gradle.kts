plugins {
    id("java")
    id("io.papermc.paperweight.userdev")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    paperweight.paperDevBundle("26.3.build.+")
    compileOnly(project(":nms:v1_21_R7"))
    compileOnly(project(":api"))
    pluginRemapper("net.fabricmc:tiny-remapper:0.14.0")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }
}

configurations.all {
    exclude(group = "net.kyori", module = "adventure-text-serializer-ansi")
    exclude(group = "io.netty", module = "netty-transport-native-epoll")
    exclude(group = "io.netty", module = "netty-transport-native-kqueue")
}

