package com.helix.app.companions

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.helix.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Local user-only APK handoff. Never binds a Runtime or enables an Agent capability. */
class BundledRuntimeInstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = intent.getStringExtra("runtime")
        if (runtime !in setOf("subscriptions", "proot")) {
            finish()
            return
        }
        setContent { MaterialTheme { Installer(requireNotNull(runtime), savedInstanceState == null) } }
    }

    @Composable
    @Suppress("FunctionName", "LongMethod", "SwallowedException")
    private fun Installer(
        runtime: String,
        fresh: Boolean,
    ) {
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var permission by remember { mutableStateOf(packageManager.canRequestPackageInstalls()) }
        val scope = rememberCoroutineScope()
        val install = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finish() }
        val prepare: () -> Unit = {
            if (!busy) {
                scope.launch {
                    busy = true
                    failed = false
                    try {
                        val apk = withContext(Dispatchers.IO) { prepareApk(runtime) }
                        val uri =
                            FileProvider.getUriForFile(
                                this@BundledRuntimeInstallActivity,
                                "$packageName.runtime-apks",
                                apk,
                            )
                        install.launch(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                    } catch (_: IOException) {
                        failed = true
                    } catch (_: IllegalArgumentException) {
                        failed = true
                    } catch (_: android.content.ActivityNotFoundException) {
                        failed = true
                    } catch (_: SecurityException) {
                        failed = true
                    } finally {
                        busy = false
                    }
                }
            }
        }
        val allow =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                permission = packageManager.canRequestPackageInstalls()
                if (permission) prepare()
            }
        LaunchedEffect(Unit) {
            if (fresh) {
                if (permission) {
                    prepare()
                } else {
                    allow.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                }
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(
                    if (runtime ==
                        "subscriptions"
                    ) {
                        R.string.bundled_subscriptions
                    } else {
                        R.string.bundled_proot
                    },
                ),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.bundled_runtime_note))
            if (!permission) Text(stringResource(R.string.bundled_install_permission))
            if (busy) Text(stringResource(R.string.bundled_install_working))
            if (failed) Text(stringResource(R.string.bundled_install_failed), color = MaterialTheme.colorScheme.error)
            OutlinedButton(enabled = !busy, onClick = {
                if (permission) {
                    prepare()
                } else {
                    allow.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                }
            }) { Text(stringResource(R.string.bundled_install_continue)) }
        }
    }

    @Suppress("DEPRECATION") // PackageInfo APIs must also run on API 29.
    private fun prepareApk(runtime: String): File {
        val expectedPackage = if (runtime == "subscriptions") "com.helix.runtime.cli" else "com.helix.runtime.proot"
        val directory = File(cacheDir, "runtime-apks").apply { mkdirs() }
        val apk = File.createTempFile("$runtime-", ".apk", directory)
        try {
            assets.open("companions/$runtime.apk").use { input -> apk.outputStream().use(input::copyTo) }
            val archive =
                requireNotNull(packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES))
            val own = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers =
                archive.signingInfo
                    ?.apkContentsSigners
                    ?.map { it.toCharsString() }
                    ?.toSet()
                    .orEmpty()
            val hostSigners =
                own.signingInfo
                    ?.apkContentsSigners
                    ?.map { it.toCharsString() }
                    ?.toSet()
                    .orEmpty()
            require(RuntimeApkPolicy.accepts(expectedPackage, archive.packageName, hostSigners, signers))
            val cached = File(directory, "$runtime.apk")
            java.nio.file.Files.move(
                apk.toPath(),
                cached.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            return cached
        } finally {
            apk.delete()
        }
    }
}
