# Historical one-shot implementation script from 2026-09-10.
# Preserved for provenance; do not rerun against current source.
from pathlib import Path
p=Path('app/build.gradle.kts');s=p.read_text();s+='''
// Package the matching companion build outputs; never commit APK binaries to source control.
androidComponents {
    onVariants(selector().withFlavor("distribution" to "developer")) { variant ->
        val buildType = requireNotNull(variant.buildType)
        val title = buildType.replaceFirstChar { it.uppercase() }
        val copy = tasks.register<Sync>("embed${variant.name.replaceFirstChar { it.uppercase() }}Runtimes") {
            dependsOn(":runtime:cli-app:assemble$title", ":runtime:proot-app:assemble$title")
            into(layout.buildDirectory.dir("generated/runtimeAssets/${variant.name}"))
            from(rootProject.layout.projectDirectory.dir("runtime/cli-app/build/outputs/apk/$buildType")) {
                include("*.apk")
                rename { "subscriptions.apk" }
                into("companions")
            }
            from(rootProject.layout.projectDirectory.dir("runtime/proot-app/build/outputs/apk/$buildType")) {
                include("*.apk")
                rename { "proot.apk" }
                into("companions")
            }
            duplicatesStrategy = DuplicatesStrategy.FAIL
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy) { it.destinationDir }
    }
}
''';# Sync destinationDir File incompatible API DirectoryProperty use static assets dependency instead
s=s.replace('        variant.sources.assets?.addGeneratedSourceDirectory(copy) { it.destinationDir }','''        variant.sources.assets?.addStaticSourceDirectory("build/generated/runtimeAssets/${variant.name}")
        tasks.matching { it.name == "merge${variant.name.replaceFirstChar { it.uppercase() }}Assets" }
            .configureEach { dependsOn(copy) }''');p.write_text(s)
for flavor in ['developer','consumer']:
 directory=Path(f'app/src/{flavor}/kotlin/com/helix/app/companions');directory.mkdir(parents=True,exist_ok=True)
 if flavor=='consumer':
  (directory/'BundledRuntimeSection.kt').write_text('''package com.helix.app.companions

import androidx.compose.runtime.Composable

@Composable
@Suppress("FunctionName")
internal fun BundledRuntimeSection() = Unit
''')
 else:
  (directory/'BundledRuntimeSection.kt').write_text('''package com.helix.app.companions

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.SettingsActions

@Composable
@Suppress("FunctionName")
internal fun BundledRuntimeSection() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bundled_runtime_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.bundled_runtime_note), style = MaterialTheme.typography.bodySmall)
        SettingsActions {
            listOf("subscriptions" to R.string.bundled_subscriptions, "proot" to R.string.bundled_proot).forEach { (id, label) ->
                OutlinedButton({
                    context.startActivity(Intent(context, BundledRuntimeInstallActivity::class.java).putExtra("runtime", id))
                }) { Text(stringResource(label)) }
            }
        }
    }
}
''')
p=Path('app/src/main/kotlin/com/helix/app/ui/SettingsScreen.kt');s=p.read_text().replace('        SettingsGroup { LanguageSection() }','        com.helix.app.companions.BundledRuntimeSection()\n\n        SettingsGroup { LanguageSection() }');p.write_text(s)
p=Path('app/src/developer/AndroidManifest.xml');s=p.read_text().replace('    <queries>','    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />\n    <queries>\n        <package android:name="com.helix.runtime.proot" />');s=s.replace('        <meta-data','''        <activity android:name="com.helix.app.companions.BundledRuntimeInstallActivity" android:exported="false" />
        <provider android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.runtime-apks"
            android:exported="false" android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/runtime_apk_paths" />
        </provider>
        <meta-data''',1);p.write_text(s)
p=Path('app/src/developer/res/xml/runtime_apk_paths.xml');p.parent.mkdir(parents=True,exist_ok=True);p.write_text('''<?xml version="1.0" encoding="utf-8"?>
<paths><cache-path name="runtimes" path="runtime-apks/" /></paths>
''')
strings={
'bundled_runtime_title':('内置组件','Bundled components'),
'bundled_runtime_note':('安装包随主应用提供，无需下载。安装或更新需系统确认；PRoot 安装后请初始化并验证。','Installers are bundled for offline use. Android confirms installation or updates. Initialize and verify PRoot after installation.'),
'bundled_subscriptions':('安装 / 更新 Subscriptions','Install / update Subscriptions'),
'bundled_proot':('安装 / 更新 PRoot','Install / update PRoot'),
'bundled_install_working':('正在准备安装包…','Preparing installer…'),
'bundled_install_failed':('无法安装：安装包缺失、损坏、签名不匹配或系统不允许。请确认主应用与组件来自同一发行版本。','Cannot install: package missing, invalid, signature mismatch, or installation blocked. Use the main app and components from the same release.'),
'bundled_install_permission':('请允许 Helix 安装应用，返回后继续安装。','Allow Helix to install apps, then return to continue.'),
'bundled_install_continue':('继续安装','Continue installation'),
}
for d in ['values','values-en','values-zh-rCN']:
 p=Path('app/src/main/res')/d/'strings.xml';s=p.read_text().replace('</resources>',''.join(f'    <string name="{k}">{v[1 if d=="values-en" else 0]}</string>\n' for k,v in strings.items())+'</resources>');p.write_text(s)
