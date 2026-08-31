plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.helix.runtime.cli.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.helix.runtime.cli"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

// Test baseline (matches the root build's android-library convention): the first M9
// instrumented/JVM test does not need to re-declare the platform dependency.
dependencies {
    testImplementation(libs.junit4)
}
