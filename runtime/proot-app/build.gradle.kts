import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
}

// A pinned APK input, not a device download or a second Runtime.
val ffmpegArchive = layout.projectDirectory.file("vendor/ffmpeg-9.0.2-ready-av1-arm64-v8a.zip")
val ffmpegDirectory = layout.buildDirectory.dir("generated/ffmpeg")
val prepareFfmpeg =
    tasks.register<Exec>("prepareFfmpeg") {
        inputs.file(ffmpegArchive)
        inputs.file(rootProject.file("scripts/prepare-ffmpeg-proot.py"))
        outputs.dir(ffmpegDirectory)
        commandLine(
            "python3",
            rootProject.file("scripts/prepare-ffmpeg-proot.py").absolutePath,
            ffmpegArchive.asFile.absolutePath,
            ffmpegDirectory.get().asFile.absolutePath,
        )
    }
tasks.named("preBuild") { dependsOn(prepareFfmpeg) }

extensions.configure<LibraryExtension> {
    sourceSets.getByName("main") {
        jniLibs.srcDir(ffmpegDirectory.get().dir("jniLibs").asFile)
        assets.srcDir(ffmpegDirectory.get().dir("assets").asFile)
    }
    packaging.jniLibs.keepDebugSymbols +=
        setOf("**/libav*.so", "**/libsw*.so", "**/libhelix_ffmpeg.so", "**/libhelix_ffprobe.so")
}

android {
    namespace = "com.helix.runtime.proot.app"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // HXA-084: the native getpagesize() seam (src/main/cpp).
        externalNativeBuild {
            cmake {
                abiFilters("arm64-v8a", "x86_64")
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // HXA-083: the handshake descriptor reports the companion's versionName; AGP 8
    // disables BuildConfig by default.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }

    // The job's native loader (libhelix_loader.so) must be EXECUTABLE by the
    // system linker at runtime; with on-demand extraction (the AGP default) it is
    // never materialized as a file. Extracting at install places it in the APK
    // install dir (apk_data_file: execute + execute_no_trans are granted to
    // appdomain), which is exactly where it is needed.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Test baseline (matches the root build's android-library convention): the first M8
// instrumented/JVM test does not need to re-declare the platform dependency.
dependencies {
    testImplementation(libs.junit4)
    // HXA-080: the Runtime APK owns the embedded runtime-lock.json / manifest / license
    // schema (architecture doc local-code-execution section 6.3 唯一版本真相); the installer
    // (HXA-082) and the license page (HXA-087) parse it with the shared :runtime:proot-core
    // codec. The androidTest below proves the codec runs on the Android runtime (API 29/36).
    implementation(project(":runtime:proot-core"))
    // HXA-083: the cross-APK hand-rolled protocol (handshake, PFD manifest channel)
    // is shared with the main app's :runtime:proot-client — both bundle the same
    // wire constants so the protocol cannot drift.
    implementation(project(":runtime:proot-ipc"))
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
