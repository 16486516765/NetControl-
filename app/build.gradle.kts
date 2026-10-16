import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64
plugins {
    id("com.android.application")
    // AGP 9.0+ 内置 Kotlin 支持，不再需要 org.jetbrains.kotlin.android（抄 KSU：app 只申 compose.compiler）
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

android {
    namespace = "com.limao.netcontrol"
    // miuix 0.9.4 要求 compileSdk 37+（AAR metadata 校验）；targetSdk 保持 36，避免 Android 37 行为变更
    compileSdk = 37

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
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // MIUI 风格（抄 KernelSU）：Miuix KMP 组件库 + 液态玻璃 blur
    // miuix-ui：MiuixTheme / 基础组件；miuix-blur：液态玻璃 backdrop / shader
    // miuix-icons/nav/preference：与 KSU 保持一致的完整依赖集
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.blur)
}

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
