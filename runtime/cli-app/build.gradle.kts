plugins {
    alias(libs.plugins.android.library)
}

// Owner-approved public installed-app identity; Developer runtime only. See provider ADR 002.
val upstreamAntigravityClientId = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
val upstreamAntigravityClientSecret = "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"
val configuredAntigravityId = providers.environmentVariable("HELIX_ANTIGRAVITY_CLIENT_ID")
val configuredAntigravitySecret = providers.environmentVariable("HELIX_ANTIGRAVITY_CLIENT_SECRET")
require(configuredAntigravityId.isPresent == configuredAntigravitySecret.isPresent) {
    "Configure both Antigravity OAuth client parameters or neither"
}
val antigravityClientId = configuredAntigravityId.orElse(upstreamAntigravityClientId)
val antigravityClientSecret = configuredAntigravitySecret.orElse(upstreamAntigravityClientSecret)
require(antigravityClientId.get().isEmpty() == antigravityClientSecret.get().isEmpty()) {
    "Configure both Antigravity OAuth client parameters or neither"
}

fun oauthLiteral(value: String): String {
    require(value.length <= 512 && value.all { (it.isLetterOrDigit() && it.code < 128) || it in "._-" }) {
        "Invalid Antigravity OAuth build configuration"
    }
    return "\"$value\""
}

android {
    namespace = "com.helix.runtime.cli.app"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "ANTIGRAVITY_CLIENT_ID", oauthLiteral(antigravityClientId.get()))
        buildConfigField("String", "ANTIGRAVITY_CLIENT_SECRET", oauthLiteral(antigravityClientSecret.get()))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("release") {
            // Opt-in acceptance artifact; ordinary release packaging retains its current behavior.
            isMinifyEnabled = providers.gradleProperty("helix.cli.r8").orNull == "true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
    implementation(project(":runtime:cli-client"))
    implementation(project(":core:model"))
    implementation(project(":core:policy"))
    implementation(project(":provider:api"))
    implementation(project(":provider:openai-responses"))
    implementation(project(":provider:anthropic"))
    implementation(project(":provider:openai-chat"))
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
