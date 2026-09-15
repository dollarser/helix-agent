import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

/** Local fail-closed patch to the locked JGit artifact. No upstream/cache files are modified. */
@CacheableTransform
abstract class RejectInsecureJgit : TransformAction<TransformParameters.None> {
    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val input = inputArtifact.get().asFile
        if (!input.name.startsWith("org.eclipse.jgit-")) {
            outputs.file(input)
            return
        }
        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(input.readBytes())
                .joinToString("") { "%02x".format(it) }
        check(digest == "43f92f3adb681a5f3006b979e8d341c12a8cfd8029f287c42bcf0a80377565ae") {
            "JGit artifact changed: review the fail-closed TLS patch before changing the pinned dependency"
        }
        val temporary = Files.createTempDirectory("helix-jgit-compile").toFile()
        try {
            val source = temporary.resolve("NoCheckX509TrustManager.java")
            source.writeText(
                """
                package org.eclipse.jgit.transport.http;
                // Helix-authored fail-closed replacement; see config/jgit/README.md.
                public class NoCheckX509TrustManager implements javax.net.ssl.X509TrustManager {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                        throw new java.security.cert.CertificateException("Helix does not support disabling TLS verification");
                    }
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                        throw new java.security.cert.CertificateException("Helix does not support disabling TLS verification");
                    }
                }
                """.trimIndent(),
            )
            val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "The build requires JDK17" }
            check(compiler.run(null, null, null, "--release", "8", "-g:none", "-d", temporary.path, source.path) == 0)
            val target = "org/eclipse/jgit/transport/http/NoCheckX509TrustManager.class"
            val replacement = temporary.resolve(target).readBytes()
            val output = outputs.file(input.nameWithoutExtension + "-helix-tls.jar")
            ZipFile(input).use { original ->
                check(original.getEntry(target) != null)
                ZipOutputStream(output.outputStream()).use { patched ->
                    original.entries().asSequence().sortedBy { it.name }.forEach { entry ->
                        // The upstream signature cannot authenticate locally changed bytes.
                        val signature = entry.name.matches(Regex("META-INF/[^/]+\\.(SF|RSA|DSA|EC)"))
                        if (!signature) {
                            patched.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                            if (entry.name == target) {
                                patched.write(replacement)
                            } else {
                                original.getInputStream(entry).use { it.copyTo(patched) }
                            }
                            patched.closeEntry()
                        }
                    }
                }
            }
        } finally {
            temporary.deleteRecursively()
        }
    }
}

val hardened = Attribute.of("com.helix.jgit.reject-insecure-tls", Boolean::class.javaObjectType)
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
    attributesSchema.attribute(hardened)
    artifactTypes.getByName("jar").attributes.attribute(hardened, false)
    registerTransform(RejectInsecureJgit::class) {
        from.attribute(hardened, false).attribute(artifactType, "jar")
        to.attribute(hardened, true).attribute(artifactType, "jar")
    }
}
val helixJgit =
    configurations.create("helixJgit") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
dependencies {
    add(
        helixJgit.name,
        project.extensions
            .getByType<VersionCatalogsExtension>()
            .named("libs")
            .findLibrary("jgit")
            .get(),
    )
    add(
        "implementation",
        files(
            helixJgit.incoming
                .artifactView {
                    attributes.attribute(hardened, true)
                }.files,
        ),
    )
    // Pinned upstream POM dependencies use normal conflict resolution, never duplicate file jars.
    add("implementation", "com.googlecode.javaewah:JavaEWAH:1.2.3")
    add("implementation", "org.slf4j:slf4j-api:1.7.36")
    add("implementation", "commons-codec:commons-codec:1.17.0")
}
