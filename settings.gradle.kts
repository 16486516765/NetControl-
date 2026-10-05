pluginManagement {
    repositories {
        // 本地 file:// Maven 仓库（预下载，绕开本地 JVM 代理隧道 bug）；CI 环境会自动顺延到下面的网络仓库
        maven {
            url = uri("file:///home/hatch/workspace/android-dev/maven-cache/")
        }
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri("file:///home/hatch/workspace/android-dev/maven-cache/")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "NetControl"
include(":app")
