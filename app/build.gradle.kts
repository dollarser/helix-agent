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
        testInstrumentationRunner = "com.helix.app.HelixAndroidJUnitRunner"
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

    bundle {
        // HXA-069: in-app language switching (AppLanguageStore) ships all locales in the base APK;
        // disable the per-locale bundle split or a runtime locale change would need the Play App
        // Language library to download the locale (lint: AppBundleLocaleChanges).
        language {
            enableSplit = false
        }
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
    // HXA-071: client-only MCP configuration/handshake and the storage bridge. SDK/Ktor
    // types remain behind :extensions:mcp's Helix-owned facade.
    implementation(project(":extensions:mcp"))
    // HXA-078: client-only A2A configuration and Agent Card discovery/snapshot. The transport
    // remains behind :extensions:a2a's Helix-owned facade; dynamic tools arrive in HXA-079.
    implementation(project(":extensions:a2a"))
    // HXA-074..076: Agent Skills validation, immutable imports/snapshots, built-in catalog,
    // and the skills.* tools. Skill instructions never bypass the normal tool pipeline.
    implementation(project(":extensions:skills"))
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
    // HXA-083: the supervisor's public API exposes the shared cross-APK protocol types
    // (availability, cause, connection results); the developer flavor declares the
    // protocol module directly, same convention as :provider:api.
    add("developerImplementation", project(":runtime:proot-ipc"))
    // HXA-084: the job E2E packs the input archive with the shared core codec.
    add("developerImplementation", project(":runtime:proot-core"))
    add("developerImplementation", project(":runtime:cli-client"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // HXA-069: in-app language switching (AppLanguageStore) drives the API 33+ system "App
    // languages" two-way sync through the framework android.app.LocaleManager service, so it
    // needs no extra dependency (a plain ComponentActivity is enough — no appcompat required).
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.work.runtime.ktx)
    // P0-B Git status / diff / changed files: on-device (native) git reader for the
    // workspace repo. Pure JVM; works in both flavors with no PRoot runtime required.
    implementation(libs.jgit)

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

// Package matching outputs as a generated asset source, including lint/model task dependencies.
abstract class BundleRuntimeApks : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val subscriptionApks: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val prootApks: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val directory = outputDirectory.dir("companions").get().asFile
        check(directory.mkdirs() || directory.isDirectory)
        subscriptionApks.files.single().copyTo(directory.resolve("subscriptions.apk"), overwrite = true)
        prootApks.files.single().copyTo(directory.resolve("proot.apk"), overwrite = true)
    }
}

androidComponents {
    onVariants(selector().withFlavor("distribution" to "developer")) { variant ->
        val buildType = requireNotNull(variant.buildType)
        val title = buildType.replaceFirstChar { it.uppercase() }
        val copy =
            tasks.register<BundleRuntimeApks>("embed${variant.name.replaceFirstChar { it.uppercase() }}Runtimes") {
                dependsOn(":runtime:cli-app:assemble$title", ":runtime:proot-app:assemble$title")
                outputDirectory.set(layout.buildDirectory.dir("generated/runtimeAssets/${variant.name}"))
                subscriptionApks.from(
                    rootProject.fileTree("runtime/cli-app/build/outputs/apk/$buildType") { include("*.apk") },
                )
                prootApks.from(
                    rootProject.fileTree("runtime/proot-app/build/outputs/apk/$buildType") { include("*.apk") },
                )
            }
        variant.sources.assets?.addGeneratedSourceDirectory(copy) { it.outputDirectory }
    }
}
