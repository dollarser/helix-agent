#!/usr/bin/env python3
"""Build-only lifecycle patch against a hash-verified Apache-2.0/MIT source archive."""
import hashlib
from pathlib import Path
import shutil
import tarfile

repo = Path(__file__).resolve().parents[3]
archive = repo / "build/hxa197-termlib-0.2.1/upstream.tar.gz"
expected = "c0a9a6e2f24e88cace413aa6d9ea9ddabe8d30c43fbf6e449ea86c2e411fddbc"
assert hashlib.sha256(archive.read_bytes()).hexdigest() == expected, "Source archive changed"
project = repo / "build/hxa197-termlib-build-spike"
sources = project / "pinned-source"
with tarfile.open(archive) as source:
    source.extractall(sources, filter="data")
upstream = sources / "termlib-27e024fccb2d722b47c57f5da1b4da9bca477b68"
module = project / "patched-terminal"
shutil.copytree(upstream / "lib/src/main", module / "src/main", dirs_exist_ok=True)
shutil.copyfile(upstream / "LICENSE", module / "LICENSE")

file = module / "src/main/java/org/connectbot/terminal/TerminalEmulator.kt"
code = file.read_text()
def replace_once(before, after):
    global code
    assert code.count(before) == 1, f"Patch anchor changed: {before[:80]}"
    code = code.replace(before, after)

replace_once("sealed interface TerminalEmulator {", '''sealed interface TerminalEmulator : AutoCloseable {
    /** Caller stops producers and detaches the view before closing on its callback looper. */
    override fun close()
''')
replace_once("    private var outputBatch: ByteArrayOutputStream? = null", '''    private var outputBatch: ByteArrayOutputStream? = null
    @Volatile private var closed = false

    override fun close() {
        check(Looper.myLooper() == looper) { "Close on the callback looper after stopping producers" }
        if (closed) return
        // Drain previously accepted keyboard commands without holding damageLock.
        commands.call { Unit }
        synchronized(damageLock) {
            if (closed) return
            setInlineImages(InlineImages.Off)
            closed = true
            handler.removeCallbacksAndMessages(null)
            imageStore.clear()
            terminalNative.close()
        }
    }''')
replace_once("    private fun processScheduledUpdates(): Unit = synchronized(damageLock) {", '''    private fun processScheduledUpdates(): Unit = synchronized(damageLock) {
        if (closed) return@synchronized''')
file.write_text(code)

(module / "build.gradle.kts").write_text('''plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-parcelize")
}
android {
    namespace = "org.connectbot.terminal"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
dependencies {
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
}
dependencyLocking { lockAllConfigurations() }
''')
settings = project / "settings.gradle.kts"
settings.write_text(settings.read_text() + '\ninclude(":patched-terminal")\n')
build = project / "build.gradle.kts"
build.write_text(build.read_text().replace(
    'plugins { id("com.android.application") version "9.3.2" }',
    '''plugins {
    id("com.android.application") version "9.3.2"
    id("org.jetbrains.kotlin.jvm") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}''',
).replace(
    'implementation("org.connectbot:termlib:0.2.1")', 'implementation(project(":patched-terminal"))',
).replace(
    'dependencies {', '''dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")''',
).replace(
    'namespace = "com.helix.spike.termlib"', 'namespace = "com.helix.spike.termlib"\n    buildFeatures { compose = true }',
))
tests = project / "src/androidTest/java/com/helix/spike/termlib"
shutil.copyfile(Path(__file__).parent / "pty-spike/src/androidTest/java/com/helix/spike/termlib/TerminalCloseProbeTest.kt", tests / "TerminalCloseProbeTest.kt")
shutil.copyfile(Path(__file__).parent / "pty-spike/src/androidTest/java/com/helix/spike/termlib/TerminalViewProbeTest.kt", tests / "TerminalViewProbeTest.kt")
shutil.copyfile(Path(__file__).parent / "pty-spike/src/main/java/com/helix/spike/termlib/TerminalViewProbeActivity.kt", project / "src/main/java/com/helix/spike/termlib/TerminalViewProbeActivity.kt")
manifest = project / "src/main/AndroidManifest.xml"
manifest.write_text(manifest.read_text().replace("</application>", '<activity android:name=".TerminalViewProbeActivity" android:exported="false" android:windowSoftInputMode="adjustResize" /></application>'))
print("Prepared fixed-source lifecycle patch in ignored build directory")
