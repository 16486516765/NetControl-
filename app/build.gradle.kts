import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "com.limao.netcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.limao.netcontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    configurations.all {
        exclude(group = "androidx.navigationevent", module = "navigationevent-android")
        exclude(group = "androidx.navigationevent", module = "navigationevent")
        // collection-ktx 已合并进 collection，排除旧版避免 duplicate class
        exclude(group = "androidx.collection", module = "collection-ktx")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-core:1.6.8")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 真实 Liquid Glass：Kyant0/AndroidLiquidGlass Backdrop（当前版 2.0.1）
    // 元数据已降级到 Kotlin 2.0（仅调用稳定 API）；排除 JetBrains Compose 传递依赖
    implementation("io.github.kyant0:backdrop:2.0.1") {
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    implementation("io.github.kyant0:shapes:1.2.1") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
}

// Kyant0 LiquidGlass 的 AAR 元数据声明 minCompileSdk=37，但 Google 尚未公开发布
// android-37 SDK 平台（公开仓库最高只有 android-36），此处跳过 AAR 元数据版本检查。
// 该库实际使用的图形 API 在旧版本即存在，跳过检查不影响编译与运行。
tasks.configureEach {
    if (name.contains("AarMetadata")) {
        enabled = false
    }
}
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
