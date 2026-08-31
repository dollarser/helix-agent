import com.android.build.api.dsl.LibraryExtension
import com.diffplug.gradle.spotless.SpotlessExtension
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
val kotlinxSerializationJsonDependency = libs.kotlinx.serialization.json
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
        ":core:storage" to listOf(":core:model"),
        ":core:workspace" to listOf(":core:model"),
        ":provider:api" to listOf(":core:model"),
        ":provider:openai-responses" to listOf(":provider:api", ":core:model"),
        ":provider:openai-chat" to listOf(":provider:api", ":core:model"),
        ":provider:anthropic" to listOf(":provider:api", ":core:model"),
        ":provider:catalog" to listOf(":provider:api", ":core:model"),
        ":extensions:mcp" to listOf(":core:model", ":tools:framework"),
        ":extensions:skills" to listOf(":core:model", ":tools:framework"),
        ":feature:browser" to listOf(":core:model", ":core:policy"),
        ":feature:files" to listOf(":core:model", ":core:policy"),
        ":feature:files-allfiles" to listOf(":feature:files"),
        ":runtime:quickjs" to listOf(":core:model"),
        ":runtime:proot-client" to listOf(":core:model"),
        ":runtime:cli-client" to listOf(":core:model"),
        ":tools:framework" to listOf(":core:model", ":core:policy"),
        ":tools:android" to listOf(":core:model", ":core:policy"),
        ":tools:automation" to listOf(":core:model", ":core:policy"),
        ":tools:browser" to listOf(":core:model", ":core:policy"),
        ":tools:files" to listOf(":core:model", ":core:policy", ":core:workspace"),
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
        }

        in jvmLibraries -> {
            pluginManager.apply("java-library")
            pluginManager.apply("org.jetbrains.kotlin.jvm")

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                jvmToolchain(17)
            }

            dependencies.add("testImplementation", jvmTestDependency)

            // The OpenAI Responses (HXA-022) and Chat Completions (HXA-023) adapters
            // encode request bodies and decode vendor SSE payloads with the pinned
            // kotlinx-serialization JsonElement API (no serialization compiler plugin
            // needed). HXA-024 (Anthropic) will declare the same dependency when it lands.
            if (path == ":provider:openai-responses" || path == ":provider:openai-chat") {
                dependencies.add("implementation", kotlinxSerializationJsonDependency.get())
            }
        }
    }

    projectDependencies[path].orEmpty().forEach { dependencyPath ->
        dependencies.add("implementation", project(dependencyPath))
    }
}
