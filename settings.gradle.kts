pluginManagement {
    repositories {
        // CI 环境：直接使用网络仓库（2026-10-05 缓存排查：移除本地 file:// 仓库并加注，强制刷新 setup-gradle 缓存）
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "NetControl"
include(":app")
