import com.android.build.api.dsl.LibraryExtension
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

abstract class PrepareTerminal : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @get:Internal
    abstract val javaDirectory: DirectoryProperty

    @get:Internal
    abstract val resourceDirectory: DirectoryProperty

    @get:Internal
    abstract val nativeDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val root = destination.get().asFile
        root.deleteRecursively()
        root.mkdirs()
        artifacts.files.forEach { artifact ->
            val source = artifact.extension == "jar"
            val expected =
                if (source) {
                    "fe728b29a2c0f4612de88af2126ae61fc793a3203ca7564fafcd842754a0ab03"
                } else {
                    "01a7dcb57aebdb5492abc9c505963de9d638eee8065ab074bd67b9882f7690d5"
                }
            val actual =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(artifact.readBytes())
                    .joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Review changed termlib artifact: ${artifact.name}" }
            ZipFile(artifact).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val relative =
                        when {
                            source && entry.name.endsWith(".kt") -> "java/${entry.name}"
                            !source && entry.name == "proguard.txt" -> entry.name
                            !source && entry.name.startsWith("res/") -> entry.name
                            !source && entry.name.startsWith("jni/arm64-v8a/") -> entry.name
                            !source && entry.name.startsWith("jni/x86_64/") -> entry.name
                            else -> null
                        }
                    if (relative != null) {
                        val target = root.resolve(relative).canonicalFile
                        check(target.toPath().startsWith(root.canonicalFile.toPath()))
                        target.parentFile.mkdirs()
                        zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                }
            }
        }
        val file = root.resolve("java/org/connectbot/terminal/TerminalEmulator.kt")
        var code = file.readText()

        fun replace(
            before: String,
            after: String,
        ) {
            check(code.windowed(before.length).count { it == before } == 1) { "Changed lifecycle patch anchor" }
            code = code.replace(before, after)
        }
        replace(
            "sealed interface TerminalEmulator {",
            "sealed interface TerminalEmulator : AutoCloseable {\n    override fun close()",
        )
        replace(
            "    private var outputBatch: ByteArrayOutputStream? = null",
            """
            private var outputBatch: ByteArrayOutputStream? = null
            @Volatile private var closed = false
            override fun close() {
                check(Looper.myLooper() == looper) { "Close on the callback looper after stopping producers" }
                if (closed) return
                commands.call { Unit }
                synchronized(damageLock) {
                    if (closed) return
                    setInlineImages(InlineImages.Off)
                    closed = true
                    handler.removeCallbacksAndMessages(null)
                    imageStore.clear()
                    terminalNative.close()
                }
            }
            """.trimIndent(),
        )
        replace(
            "    private fun processScheduledUpdates(): Unit = synchronized(damageLock) {",
            "    private fun processScheduledUpdates(): Unit = synchronized(damageLock) {\n        if (closed) return@synchronized",
        )
        file.writeText(code)
    }
}

val terminalArtifacts by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareTerminal =
    tasks.register<PrepareTerminal>("prepareTerminal") {
        artifacts.from(terminalArtifacts)
        destination.set(layout.buildDirectory.dir("generated/terminal"))
        javaDirectory.set(destination.dir("java"))
        resourceDirectory.set(destination.dir("res"))
        nativeDirectory.set(destination.dir("jni"))
    }

extensions.configure<LibraryExtension> {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        consumerProguardFiles(
            layout.buildDirectory
                .file("generated/terminal/proguard.txt")
                .get()
                .asFile,
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        abortOnError = true
        warningsAsErrors = true
        lintConfig = rootProject.file("config/lint/lint.xml")
    }
}

tasks.named("preBuild") { dependsOn(prepareTerminal) }

androidComponents.onVariants { variant ->
    variant.sources.java?.addGeneratedSourceDirectory(prepareTerminal) { it.javaDirectory }
    variant.sources.res?.addGeneratedSourceDirectory(prepareTerminal) { it.resourceDirectory }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareTerminal) { it.nativeDirectory }
}

dependencies {
    terminalArtifacts("org.connectbot:termlib:${libs.versions.termlib.get()}:sources@jar")
    terminalArtifacts("org.connectbot:termlib:${libs.versions.termlib.get()}@aar")
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
