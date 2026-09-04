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
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
