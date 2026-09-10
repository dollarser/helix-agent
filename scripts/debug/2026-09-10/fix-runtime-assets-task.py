# Historical one-shot implementation script from 2026-09-10.
# Preserved for provenance; do not rerun against current source.
from pathlib import Path
p=Path('app/build.gradle.kts');s=p.read_text();start=s.index('// Package the matching companion build outputs;');s=s[:start]+'''// Package matching outputs as a generated asset source, including lint/model task dependencies.
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
        val copy = tasks.register<BundleRuntimeApks>("embed${variant.name.replaceFirstChar { it.uppercase() }}Runtimes") {
            dependsOn(":runtime:cli-app:assemble$title", ":runtime:proot-app:assemble$title")
            outputDirectory.set(layout.buildDirectory.dir("generated/runtimeAssets/${variant.name}"))
            subscriptionApks.from(rootProject.fileTree("runtime/cli-app/build/outputs/apk/$buildType") { include("*.apk") })
            prootApks.from(rootProject.fileTree("runtime/proot-app/build/outputs/apk/$buildType") { include("*.apk") })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy) { it.outputDirectory }
    }
}
''';p.write_text(s)
