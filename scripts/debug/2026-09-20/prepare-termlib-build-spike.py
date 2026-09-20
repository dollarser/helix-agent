#!/usr/bin/env python3
"""Prepare an isolated compile-only probe; does not add a production dependency."""
from pathlib import Path

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
    }
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
    <application android:label="Terminal compile probe" />
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
