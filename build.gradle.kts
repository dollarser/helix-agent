import com.android.build.api.dsl.LibraryExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.spotless)
}

val detektCli by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    detektCli(libs.detekt.cli)
}

dependencyLocking {
    lockAllConfigurations()
}

extensions.configure<SpotlessExtension> {
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint(libs.versions.ktlint.get())
    }
    format("misc") {
        target("*.md", "*.properties", ".gitignore", "scripts/*.sh", ".github/workflows/*.yml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.register<JavaExec>("detekt") {
    group = "verification"
    description = "Runs the pinned Detekt CLI over Helix Kotlin sources."
    classpath = detektCli
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")

    val reportDirectory = layout.buildDirectory.dir("reports/detekt")
    inputs.files(
        fileTree(projectDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**")
        },
    )
    inputs.file(layout.projectDirectory.file("config/detekt/detekt.yml"))
    outputs.dir(reportDirectory)

    doFirst {
        reportDirectory.get().asFile.mkdirs()
    }

    args(
        "--input",
        projectDir.absolutePath,
        "--config",
        layout.projectDirectory
            .file("config/detekt/detekt.yml")
            .asFile.absolutePath,
        "--build-upon-default-config",
        "--parallel",
        "--excludes",
        "**/build/**,**/.gradle/**,**/*.gradle.kts",
        "--report",
        "html:${reportDirectory.get().file("detekt.html").asFile.absolutePath}",
        "--report",
        "sarif:${reportDirectory.get().file("detekt.sarif").asFile.absolutePath}",
    )
}

val androidLibraries =
    mapOf(
        ":core:storage" to "com.helix.core.storage",
        ":feature:browser" to "com.helix.feature.browser",
        ":feature:files" to "com.helix.feature.files",
        ":feature:files-allfiles" to "com.helix.feature.files.allfiles",
        ":runtime:quickjs" to "com.helix.runtime.quickjs",
        ":runtime:proot-client" to "com.helix.runtime.proot.client",
        ":runtime:cli-client" to "com.helix.runtime.cli.client",
        ":tools:android" to "com.helix.tools.android",
        ":tools:automation" to "com.helix.tools.automation",
        ":tools:browser" to "com.helix.tools.browser",
        ":tools:root" to "com.helix.tools.root",
    )

val jvmTestDependency = libs.junit4
val ziplineDependency = libs.zipline
val kotlinxSerializationJsonDependency = libs.kotlinx.serialization.json
val coroutinesCoreDependency = libs.kotlinx.coroutines.core
val okhttpDependency = libs.okhttp.wire
val roomRuntimeDependency = libs.room.runtime
val roomKtxDependency = libs.room.ktx
val roomCompilerDependency = libs.room.compiler
val roomTestingDependency = libs.room.testing
val androidTestCoreKtxDependency = libs.androidx.test.core.ktx
val androidTestRunnerDependency = libs.androidx.test.runner
val androidTestJunitDependency = libs.androidx.test.junit

val jvmLibraries =
    setOf(
        ":core:model",
        ":core:agent",
        ":core:policy",
        ":core:workspace",
        ":provider:api",
        ":provider:openai-responses",
        ":provider:openai-chat",
        ":provider:anthropic",
        ":provider:catalog",
        ":extensions:mcp",
        ":extensions:skills",
        ":tools:framework",
        ":tools:files",
        ":testing",
    )

val projectDependencies =
    mapOf(
        ":core:agent" to listOf(":core:model"),
        ":core:policy" to listOf(":core:model"),
        ":core:storage" to listOf(":core:model", ":core:policy"),
        ":core:workspace" to listOf(":core:model"),
        ":provider:api" to listOf(":core:model"),
        ":provider:openai-responses" to listOf(":provider:api", ":core:model"),
        ":provider:openai-chat" to listOf(":provider:api", ":core:model"),
        ":provider:anthropic" to listOf(":provider:api", ":core:model"),
        ":provider:catalog" to listOf(":provider:api", ":core:model"),
        ":extensions:mcp" to listOf(":core:model", ":tools:framework"),
        ":extensions:skills" to listOf(":core:model", ":tools:framework"),
        ":feature:browser" to listOf(":core:model", ":core:policy"),
        ":feature:files" to listOf(":core:model", ":core:policy", ":core:workspace"),
        ":feature:files-allfiles" to listOf(":feature:files", ":core:workspace"),
        // HXA-053: the QuickJS module hosts the `code.javascript.run` tool (descriptor +
        // executor), a tools:framework contract; the app wires it into the pipeline. A JVM
        // tool-framework dependency of an Android library mirrors :runtime:proot-client.
        ":runtime:quickjs" to listOf(":core:model", ":tools:framework"),
        ":runtime:proot-client" to listOf(":core:model"),
        ":runtime:cli-client" to listOf(":core:model"),
        ":tools:framework" to listOf(":core:model", ":core:policy"),
        ":tools:android" to listOf(":core:model", ":core:policy"),
        ":tools:automation" to listOf(":core:model", ":core:policy"),
        ":tools:browser" to listOf(":core:model", ":core:policy"),
        ":tools:files" to listOf(":core:model", ":core:policy", ":core:workspace", ":tools:framework"),
        ":tools:root" to listOf(":core:model", ":core:policy"),
        ":testing" to listOf(":core:model"),
    )

subprojects {
    group = "com.helix"
    version = "0.1.0-SNAPSHOT"

    dependencyLocking {
        lockAllConfigurations()
    }

    when (path) {
        in androidLibraries -> {
            pluginManager.apply("com.android.library")

            // Uniform test baseline for every android library: a module adding its first
            // JVM test (or instrumented test) does not re-declare the platform dependency
            // and the version stays centralized here.
            dependencies.add("testImplementation", jvmTestDependency)

            extensions.configure<LibraryExtension> {
                namespace = androidLibraries.getValue(path)
                compileSdk = 36

                defaultConfig {
                    minSdk = 29
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

            if (path == ":core:storage") {
                pluginManager.apply("com.google.devtools.ksp")
                // Room schema export goes into the androidTest assets so the migration
                // fixture (HXA-014) can load the committed v1 schema. The Room 2.8 helper
                // loads `<databaseFqn>/<version>.json` from the asset root.
                extensions.configure<com.google.devtools.ksp.gradle.KspExtension> {
                    arg("room.schemaLocation", "$projectDir/src/androidTest/assets")
                    arg("room.incremental", "true")
                }
                // The JVM contract test (DatabaseContractTest) reads the same committed
                // export; pass the asset directory explicitly because the unit test's
                // working directory is the module directory only by Gradle convention.
                tasks.withType<Test> {
                    systemProperty(
                        "helix.schema.dir",
                        layout.projectDirectory
                            .dir("src/androidTest/assets")
                            .asFile.absolutePath,
                    )
                }
                dependencies.add("implementation", roomRuntimeDependency.get())
                dependencies.add("implementation", roomKtxDependency.get())
                dependencies.add("ksp", roomCompilerDependency.get())
                // The only android library with instrumented tests; the androidx.test
                // dependencies stay module-local (the shared baseline above is JVM only).
                dependencies.add("androidTestImplementation", androidTestCoreKtxDependency.get())
                dependencies.add("androidTestImplementation", androidTestRunnerDependency.get())
                dependencies.add("androidTestImplementation", androidTestJunitDependency.get())
                dependencies.add("androidTestImplementation", roomTestingDependency.get())
            }

            // HXA-044: the SAF adapter persists its tree-grant registry with the pinned
            // kotlinx-serialization JsonElement API (no compiler plugin, same as the provider
            // modules) and carries instrumented tests against a lying in-APK ContentProvider.
            if (path == ":feature:files") {
                dependencies.add("implementation", kotlinxSerializationJsonDependency.get())
                dependencies.add("androidTestImplementation", androidTestCoreKtxDependency.get())
                dependencies.add("androidTestImplementation", androidTestRunnerDependency.get())
                dependencies.add("androidTestImplementation", androidTestJunitDependency.get())
            }

            // HXA-045: the all-files roots store persists its registry with the same pinned
            // kotlinx-serialization JsonElement API as :feature:files (implementation-scoped, so it
            // is not visible transitively through the :feature:files project dependency).
            if (path == ":feature:files-allfiles") {
                dependencies.add("implementation", kotlinxSerializationJsonDependency.get())
            }

            // HXA-050: the QuickJS E1 backend uses the pinned Zipline 1.27.0 artifact
            // (Android variant from the version catalog) as its QuickJS/JNI base. The
            // spike instrumented tests run QuickJS in-process on device; the isolated
            // service process itself is HXA-051.
            if (path == ":runtime:quickjs") {
                // BuildConfig gates the DEBUG-only crash-injection seam in
                // JsExecutionService (release builds compile the seam out).
                extensions.configure<LibraryExtension> {
                    buildFeatures {
                        buildConfig = true
                    }
                }
                dependencies.add("implementation", ziplineDependency.get())
                dependencies.add("androidTestImplementation", androidTestCoreKtxDependency.get())
                dependencies.add("androidTestImplementation", androidTestRunnerDependency.get())
                dependencies.add("androidTestImplementation", androidTestJunitDependency.get())
                // The 16 KiB-page ELF spike test (QuickJsNativeLibraryElfTest) parses the
                // zipline-android AAR's .so files; resolve the artifact at configuration
                // time and pass the path in (same pattern as :core:storage's
                // `helix.schema.dir`). `implementation` is canBeResolved=false under AGP,
                // and the variant classpaths only exist after AGP's own afterEvaluate, so
                // resolve the resolvable debug runtime classpath in a later afterEvaluate.
                afterEvaluate {
                    val ziplineAarPath =
                        configurations
                            .getByName("debugRuntimeClasspath")
                            .incoming
                            .artifactView {
                                lenient(true)
                                componentFilter {
                                    (it as? ModuleComponentIdentifier)?.module == "zipline-android"
                                }
                            }.files
                            .first { it.name.endsWith(".aar") }
                            .absolutePath
                    tasks.withType<Test>().configureEach {
                        systemProperty("helix.zipline.aar", ziplineAarPath)
                    }
                }
            }
        }

        in jvmLibraries -> {
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            // HXA-042: the JVM `:tools:files` module and the Android `:feature:files` module both
            // default to the coordinate `com.helix:files:0.1.0-SNAPSHOT`. `:app` depends on BOTH
            // (`:feature:files` from before; `:tools:files` joins now), so Gradle conflict-resolves
            // the two SNAPSHOT versions to a single artifact and silently drops the file TOOL
            // classes from the classpath. A distinct group disambiguates the two WITHOUT touching
            // the API, the artifact name, or any external lockfile (project deps are not locked).
            if (path == ":tools:files") {
                group = "com.helix.tools"
            }

            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                jvmToolchain(17)
            }

            dependencies.add("testImplementation", jvmTestDependency)

            // The provider adapters (HXA-022 Responses, HXA-023 Chat Completions,
            // HXA-024 Anthropic Messages) encode request bodies and decode vendor
            // SSE payloads with the pinned kotlinx-serialization JsonElement API
            // (no serialization compiler plugin needed). provider:api (HXA-025)
            // uses the same API for the capability snapshot wire form.
            if (
                path == ":provider:openai-responses" ||
                path == ":provider:openai-chat" ||
                path == ":provider:anthropic" ||
                path == ":provider:api"
            ) {
                dependencies.add("implementation", kotlinxSerializationJsonDependency.get())
            }

            // HXA-030: tools:framework carries the tool input/output schemas as kotlinx
            // JsonElement (the JsonObject of doc 02 section 7). `api` scope: the type is
            // part of the public ToolDescriptor contract, so consumers must see it. Same
            // pinned catalog artifact (1.9.0) as the provider modules — no new version.
            if (path == ":tools:framework") {
                dependencies.add("api", kotlinxSerializationJsonDependency.get())
            }

            // HXA-025: provider:api owns the ModelProvider contract (suspend/Flow),
            // the OkHttp transport and the capability probe. Coroutines are exposed
            // as `api` because the interface signatures use Flow/suspend; OkHttp is
            // implementation-scoped behind the WireClient seam (no vendor HTTP types
            // leak into the public API or the adapter modules).
            if (path == ":provider:api") {
                dependencies.add("api", coroutinesCoreDependency.get())
                dependencies.add("implementation", okhttpDependency.get())
            }
        }
    }

    projectDependencies[path].orEmpty().forEach { dependencyPath ->
        dependencies.add("implementation", project(dependencyPath))
    }
}
