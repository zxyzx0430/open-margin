pluginManagement {
    repositories {
        // 阿里云今天 502 不稳定，先全部走官方仓库
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack 的 sherpa-onnx AAR（com.github.k2-fsa:sherpa-onnx）。
        // 注意：App 实际打包的是静态链接 onnxruntime 的 AAR（文件依赖，见
        // android/app/build.gradle.kts 与 download-asr-model.sh）：JitPack 版自带
        // libonnxruntime.so，会和 paddle-ocr 的 ORT 撞名导致其中一个引擎加载失败。
        // 保留该仓库便于按版本核对 Kotlin API / 将来切换。
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "BookkeepingApp"
include(":app")
include(":paddle-ocr-sdk")
