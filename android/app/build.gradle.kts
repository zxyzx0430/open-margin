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
        // Stable v1.2.0: all capture/import/classification/UI work plus the
        // rc.13 startup and duplicate-notification fixes.
        // Keep in sync with UpdateVersionPolicy.CURRENT_TAG (v1.3.0).
        versionCode = 25
        versionName = "1.3.0"

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
            // 真机只有 arm64：把 x86/x86_64/armeabi-v7a 的 OCR 与 onnx 库去掉，
            // 包体从 ~77MB 降到 ~45MB。模拟器请用 debug 变体。
            ndk {
                abiFilters.clear()
                abiFilters += "arm64-v8a"
            }
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
    implementation(files(rootProject.file("paddle-ocr-sdk/libs/onnxruntime-reduced-arm64-v8a.aar")))
    implementation(files(rootProject.file("paddle-ocr-sdk/libs/opencv-minimal-arm64-v8a.aar")))
    implementation(libs.mlkit.text.recognition.chinese)

    // Background upload; default disabled until opt-in
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Offline QR pairing: embedded camera scanner + core decoder for gallery images.
    implementation(libs.zxing.android.embedded)
    implementation(libs.zxing.core)

    // 测试
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests; use the real parser.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

