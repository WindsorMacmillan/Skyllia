plugins {
    id("java")
}
group = "fr.euphyllia.skyllia.hook.fastasyncworldedit"
version = "2.3"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT") { isTransitive = false }
    compileOnly("net.kyori:adventure-text-minimessage:5.2.0")
    compileOnly(project(":api"))

    compileOnly(platform("com.intellectualsites.bom:bom-newest:1.55"))

    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core")
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit") { isTransitive = false }
}

