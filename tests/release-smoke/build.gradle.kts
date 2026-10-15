plugins {
    alias(libs.plugins.android.library)
}

val smokeTarget = providers.gradleProperty("helix.smokeApplicationId").getOrElse("com.helix.validation.hostcheck")
require(smokeTarget.matches(Regex("com\\.helix\\.validation\\.[a-z][a-z0-9]*")))

android {
    namespace = "com.helix.validation.releasechecks"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["smokeTarget"] = smokeTarget
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
