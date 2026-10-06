import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
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
            // 一、7 优化：开启 R8 混淆+资源压缩（proguard 已 keep rikka.shizuku.**）
            isMinifyEnabled = true
            isShrinkResources = true
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

    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-graphics:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.foundation:foundation:1.7.8")
    // 注意：animation 组件 1.7.x 只发布到 1.7.3，没有 1.7.8（与 ui 1.7.8 混用同 minor 安全）
    implementation("androidx.compose.animation:animation:1.7.3")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // 纯 Material 3，不再使用玻璃拟态库
}

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
