plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.helix.runtime.proot.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.helix.runtime.proot"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
