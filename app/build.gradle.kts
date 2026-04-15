import org.gradle.internal.os.OperatingSystem

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kgking.setupapp"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.kgking.setupapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    sourceSets {
        getByName("main").assets.srcDirs("src/main/assets", "build/generated/blacklistAssets")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val buildBlacklistTool by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/blacklistAssets")
    val outputFile = outputDir.map { it.file("kgking_blacklist_tool") }
    inputs.file(rootProject.file("tools/kgking_blacklist_tool.c"))
    outputs.file(outputFile)

    doLast {
        val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
            ?: throw GradleException("ANDROID_SDK_ROOT/ANDROID_HOME 未设置")
        val hostTag = when {
            OperatingSystem.current().isMacOsX -> "darwin-x86_64"
            OperatingSystem.current().isWindows -> "windows-x86_64"
            else -> "linux-x86_64"
        }
        val clang = file("$sdkRoot/ndk/26.3.11579264/toolchains/llvm/prebuilt/$hostTag/bin/aarch64-linux-android26-clang")
        if (!clang.exists()) {
            throw GradleException("未找到 NDK clang: ${clang.absolutePath}")
        }

        val out = outputFile.get().asFile
        out.parentFile.mkdirs()

        exec {
            commandLine(
                clang.absolutePath,
                "-O2",
                "-static",
                "-s",
                rootProject.file("tools/kgking_blacklist_tool.c").absolutePath,
                "-o",
                out.absolutePath,
            )
        }

        if (!out.setExecutable(true, false)) {
            throw GradleException("无法设置可执行权限: ${out.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildBlacklistTool)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
