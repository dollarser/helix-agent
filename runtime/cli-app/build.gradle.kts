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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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

// Test baseline (matches the root build's android-library convention): the first M9
// instrumented/JVM test does not need to re-declare the platform dependency.
dependencies {
    testImplementation(libs.junit4)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.wire)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

// OkHttp 5.5's Android selector requires compileSdk 37, while Helix remains on 36.
// The JVM artifact supplies the same API used by this bounded HTTPS OAuth client.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("com.squareup.okhttp3:okhttp"))
            .using(module("com.squareup.okhttp3:okhttp-jvm:${libs.versions.okhttp.get()}"))
    }
}
