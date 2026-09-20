#!/usr/bin/env python3
"""Prepare isolated renderer compilation and private-process PTY feasibility probes."""
from pathlib import Path
import shutil

root = Path(__file__).resolve().parents[3] / "build/hxa197-termlib-build-spike"
files = {
    "settings.gradle.kts": '''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "termlib-compatibility-probe"
''',
    "build.gradle.kts": '''plugins { id("com.android.application") version "9.3.2" }
android {
    namespace = "com.helix.spike.termlib"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.helix.spike.termlib"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "probe"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("org.connectbot:termlib:0.2.1")
}
dependencyLocking { lockAllConfigurations() }
''',
    "gradle.properties": "android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8\n",
    "src/main/AndroidManifest.xml": '''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Terminal probe">
        <uses-library android:name="android.test.runner" android:required="false" />
        <service android:name=".PtyProbeService" android:exported="false" android:process=":pty" />
    </application>
</manifest>
''',
    "src/main/java/com/helix/spike/termlib/ApiProbe.java": '''package com.helix.spike.termlib;

import org.connectbot.terminal.TerminalEmulator;

/** Compile only: no terminal creation, native execution, or device claims. */
public final class ApiProbe {
    private ApiProbe() {}

    public static void feed(TerminalEmulator emulator, byte[] bytes) {
        emulator.writeInput(bytes, 0, bytes.length);
    }

    public static void resize(TerminalEmulator emulator, int rows, int columns) {
        emulator.resize(rows, columns);
    }
}
''',
}
for name, content in files.items():
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content)
print(root)
sources = Path(__file__).resolve().parent / "pty-spike"
for name, destination in {
    "pty_probe.c": "src/main/cpp/pty_probe.c",
    "PtyProbeService.java": "src/main/java/com/helix/spike/termlib/PtyProbeService.java",
    "PtyProbeTest.java": "src/androidTest/java/com/helix/spike/termlib/PtyProbeTest.java",
}.items():
    target = root / destination
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(sources / name, target)
(root / "src/main/cpp/CMakeLists.txt").write_text('''cmake_minimum_required(VERSION 3.22)
project(pty_probe C)
add_library(pty_probe SHARED pty_probe.c)
target_compile_options(pty_probe PRIVATE -Wall -Wextra -Werror)
target_link_options(pty_probe PRIVATE -Wl,-z,max-page-size=16384)
''')
