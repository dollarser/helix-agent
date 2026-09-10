# Historical one-shot implementation script from 2026-09-10.
# Preserved for provenance; do not rerun against current source.
from pathlib import Path
p=Path('app/src/developer/kotlin/com/helix/app/companions/BundledRuntimeInstallActivity.kt');s=p.read_text().replace('Installer(requireNotNull(runtime))','Installer(requireNotNull(runtime), savedInstanceState == null)').replace('private fun Installer(runtime: String)', 'private fun Installer(runtime: String, fresh: Boolean)');a=s.index('        val allow =');b=s.index('        val prepare:',a);s=s[:a]+s[b:];needle='        // Launch only on fresh entry';i=s.index(needle);end=s.index('        Column(',i);s=s[:i]+'''        val allow = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permission = packageManager.canRequestPackageInstalls()
            if (permission) prepare()
        }
        LaunchedEffect(Unit) {
            if (fresh) {
                if (permission) prepare()
                else allow.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }
        }
'''+s[end:];s=s.replace('            return apk\n        } catch (error: Exception) {\n            apk.delete()\n            throw error\n        }','''            val cached = File(directory, "$runtime.apk")
            java.nio.file.Files.move(apk.toPath(), cached.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return cached
        } finally {
            apk.delete()
        }''');p.write_text(s)
