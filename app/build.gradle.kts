plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.helix.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.helix.agent"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("consumer") {
            dimension = "distribution"
        }
        create("developer") {
            dimension = "distribution"
            applicationIdSuffix = ".developer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:agent"))
    implementation(project(":core:policy"))
    // HXA-015: the recovery coordinator pairs core:agent decisions with storage writes.
    implementation(project(":core:storage"))
    implementation(project(":feature:files"))
    // HXA-060: the minimal hardened WebView browser (tabs / URL policy / downloads UI).
    implementation(project(":feature:browser"))
    // HXA-062: the browser.* tool descriptors / executors live in :tools:browser; :app calls
    // BrowserTools.registerAll directly (same direct-dep pattern as :tools:files). A distinct
    // :tools:browser group (root build.gradle.kts) keeps it from colliding with :feature:browser.
    implementation(project(":tools:browser"))
    // HXA-064: the android.open_uri / clipboard.read / clipboard.write / android.share tools.
    // Their Context-backed port impl (AndroidSystemBridgeImpl) lives in this same module, so the
    // app registers them against an instance built from the application Context.
    implementation(project(":tools:android"))
    // HXA-036: the chat flow routes model-requested tool calls through the framework
    // dispatcher (validate→capability→policy→approval→execute→verify→audit) with the
    // storage-backed approval broker and audit sink; the approval card + audit page are
    // the UI of that pipeline (doc 02 section 5.3/7.1; doc 11 唯一入口).
    implementation(project(":tools:framework"))
    // HXA-042: the first non-time.now business tools (read/write/edit/files.*) enter the
    // production tool table; their store lives in core:workspace (atomic publish + quota).
    implementation(project(":tools:files"))
    implementation(project(":core:workspace"))
    // HXA-053: the isolated QuickJS backend (the non-exported one-shot Service + the
    // main-process JsExecutionClient, ADR-0015) hosts the `code.javascript.run` tool. Shared
    // (implementation) so BOTH consumer and developer register it: ADR-0013 Standard is the
    // complete store-facing product and QuickJS (APK-embedded interpreter) is in scope for
    // consumer (local-code-execution doc section 8). The Service manifest entry merges into
    // both variants.
    implementation(project(":runtime:quickjs"))
    // HXA-028: the chat/provider UI wires the M2 provider stack into the production app
    // (provider doc section 2; ADR-0005 profile switching; ADR-0006 single main app).
    // The okhttp→okhttp-jvm substitution below covers this production classpath as well
    // (okhttp-android requires compileSdk 37; the project is pinned to 36).
    implementation(project(":provider:api"))
    implementation(project(":provider:openai-chat"))
    implementation(project(":provider:openai-responses"))
    implementation(project(":provider:anthropic"))
    implementation(project(":provider:catalog"))

    add("developerImplementation", project(":feature:files-allfiles"))
    add("developerImplementation", project(":tools:automation"))
    add("developerImplementation", project(":tools:root"))
    add("developerImplementation", project(":runtime:proot-client"))
    add("developerImplementation", project(":runtime:cli-client"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    // The production-migration device test builds a v1 fixture database (MigrationTestHelper).
    // Pinned component already locked in core:storage; no new module or version.
    androidTestImplementation(libs.room.testing)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // HXA-027: the developer instrumented smoke (SelfHostedSmokeTest, in the
    // androidTestDeveloper source set — compiled for the developer variant only)
    // drives the real provider stack (OpenAiChatProvider + OkHttpWireClient)
    // against the dev-machine model server through the emulator host bridge
    // (10.0.2.2). Test-scoped only: no production dependency is added to either
    // variant. Declared on the shared androidTest classpath because the
    // variant-specific androidTest configuration (androidTestDeveloperDebug
    // implementation) only exists after AGP variant realization, and extending
    // it from afterEvaluate breaks configuration-cache serialization of the
    // aapt2 inputs.
    androidTestImplementation(project(":provider:api"))
    androidTestImplementation(project(":provider:openai-chat"))
}

// HXA-027: OkHttp 5's platform selector resolves to the `okhttp-android` artifact
// for Android consumers, which requires compileSdk 37; this project is pinned to
// compileSdk 36 (M0 baseline). The `okhttp-jvm` artifact is the same library as
// plain JVM bytecode (no Android-specific parts), so the app's configurations
// substitute the platform selector with the JVM variant instead of a platform
// bump. The provider:api module itself keeps the normal selector (its consumers
// are JVM and resolve okhttp-jvm natively). When HXA-028 wires the provider
// stack into the production app, this substitution covers that classpath too.
configurations.all {
    resolutionStrategy.dependencySubstitution {
        // catalog-pinned version (same as provider:api resolves); only the artifact changes
        substitute(module("com.squareup.okhttp3:okhttp"))
            .using(module("com.squareup.okhttp3:okhttp-jvm:${libs.versions.okhttp.get()}"))
    }
}
