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
        versionCode = 56
        versionName = "1.47.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["openMarginProfileable"] = "false"

        // 主动截图监听（MediaStore 新截图）默认关闭；GitHub 实验版构建显式开启；
        // Play 不传该参数。分享与选图不受影响。
        buildConfigField(
            "boolean",
            "SCREENSHOT_WATCHER_AVAILABLE",
            (project.findProperty("openMarginScreenshotWatcher") as String? ?: "false"),
        )

        // 只打 arm64-v8a。真机只有 arm64；paddle-ocr/opencv 的预编译库也只有 arm64。
        // 语音的 sherpa-onnx AAR 自带 x86/x86_64 .so，如果不在这里收敛，debug 包会
        // 被系统当成 x86_64 原生应用，反而加载不了只有 arm64 的 OCR 库；
        // 模拟器继续走系统的 arm64 翻译层，和加语音之前的行为一致。
        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            // Different Actions runners produce different debug signing keys.
            // Keep the original installed app/data untouched during migration.
            applicationIdSuffix = ".sync3"
            // Opt-in performance preview retains the test package/signing key.
            // Do not change the default debug/CI instrumentation variant.
            if (providers.gradleProperty("openMarginPerformancePreview").orNull == "true") {
                isDebuggable = false
                manifestPlaceholders["openMarginProfileable"] = "true"
                signingConfig = signingConfigs.getByName("debug")
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            }
            resValue("string", "app_name", "清衡测试")
            resValue("string", "page_recognition_service_label", "清衡测试 · 页面识别")
            resValue("string", "accessibility_fallback_service_label", "清衡测试 · 转账红包兜底")
        }
        release {
            isMinifyEnabled = providers.gradleProperty("openMarginReleaseR8").orNull == "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // ABI 已在 defaultConfig 里收敛到 arm64-v8a（release 与 debug 一致）。
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

// 语音记账的本地 ASR：模型与原生库由 android/app/download-asr-model.sh 准备。
// 缺文件时在配置阶段直接失败，给出可执行的修复命令——否则会在 Kotlin 编译阶段
// 抛几十条 “Unresolved reference: k2fsa”，很难看出是模型没下载。
val asrNativeAar = rootProject.file("app/libs/sherpa-onnx-static-link-onnxruntime-1.13.5.aar")
if (!asrNativeAar.isFile) {
    throw GradleException(
        "缺少 ${asrNativeAar.name}。请先运行：bash android/app/download-asr-model.sh " +
            "（下载 ~71MB 模型 + ~36MB sherpa-onnx 原生库，带 sha256 校验，可重复执行）",
    )
}

dependencies {
    // 语音记账：本地流式 ASR 原生库（sherpa-onnx 1.13.5，静态链接 onnxruntime）。
    // 用文件依赖而不是 JitPack 坐标的原因见 download-asr-model.sh 顶部注释：
    // 带 libonnxruntime.so 的普通 AAR 会和 paddle-ocr 的 ONNX Runtime 撞名。
    implementation(files(asrNativeAar))

    // Compose & 核心
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.appcompat)
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

    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

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

