plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.bookkeeping.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bookkeeping.app"
        minSdk = 24
        targetSdk = 35
        // RC8: inline live uptime, public lock-screen visibility, ROM guidance
        // and boot restore. Keep in sync with UpdateVersionPolicy.CURRENT_TAG
        // (v1.2.0-rc.8).
        versionCode = 13
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Different Actions runners produce different debug signing keys.
            // Keep the original installed app/data untouched during migration.
            applicationIdSuffix = ".sync2"
            resValue("string", "app_name", "清衡测试")
            resValue("string", "page_recognition_service_label", "清衡测试 · 页面识别")
            resValue("string", "accessibility_fallback_service_label", "清衡测试 · 转账红包兜底")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true   // 启用 BuildConfig.DEBUG 用于日志门控
    }
}

dependencies {
    // Compose & 核心
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Navigation Compose
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Room 数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Vico 图表
    implementation(libs.vico.compose.m3)

    // 本地中文 OCR（PaddleOCR V6 Tiny；ML Kit 仅作为非 arm64 设备回退）
    implementation(project(":paddle-ocr-sdk"))
    implementation(libs.mlkit.text.recognition.chinese)

    // Background upload; default disabled until opt-in
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
