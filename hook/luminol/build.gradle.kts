plugins {
    id("java")
}

group = "fr.euphyllia.skyllia.hook.luminol"

// 本服务端项目自定义修改：Luminol 的 maven 仓库（repo.menthamc.org）已删库失效，
// 改为使用本地 Lophine 服务端构建产物（fun.bm.lophine:lophine-api，解析自 mavenLocal）。
// mavenLocal() 已由根项目的 allprojects 仓库配置提供。
// 另：本模块 toolchain 因 lophine-api 需 JVM 25，已在根项目 allprojects 中统一设置为 25。

dependencies {
    compileOnly(project(":api"))
    compileOnly("fun.bm.lophine:lophine-api:26.3.local-SNAPSHOT")
}

