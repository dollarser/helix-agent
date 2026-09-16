import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.ow2.asm:asm:9.8") }
}

/** Local fail-closed patch to the locked JGit artifact. No upstream/cache files are modified. */
@CacheableTransform
abstract class RejectInsecureJgit : TransformAction<RejectInsecureJgit.Parameters> {
    interface Parameters : TransformParameters {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.NONE)
        val streamSource: RegularFileProperty
    }

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
        check(digest == "cc63976f92e8058d05a543f320a6237accf2f17b745ab20946f246ac0b54dfd6") {
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
            check(
                compiler.run(
                    null,
                    null,
                    null,
                    "--release",
                    "8",
                    "-g:none",
                    "-d",
                    temporary.path,
                    source.path,
                    parameters.streamSource
                        .get()
                        .asFile.path,
                ) ==
                    0,
            )
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
                                val bytes = original.getInputStream(entry).use { it.readBytes() }
                                patched.write(if (entry.name.endsWith(".class")) adaptStreams(bytes) else bytes)
                            }
                            patched.closeEntry()
                        }
                    }
                    val bridge = "com/helix/jgit/AndroidInputStreams.class"
                    patched.putNextEntry(ZipEntry(bridge).apply { time = 0L })
                    patched.write(temporary.resolve(bridge).readBytes())
                    patched.closeEntry()
                }
            }
        } finally {
            temporary.deleteRecursively()
        }
    }

    private fun adaptStreams(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer = ClassWriter(reader, 0)
        var changed = false
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val delegate = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, delegate) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            descriptor: String,
                            isInterface: Boolean,
                        ) {
                            val stream =
                                owner == "java/io/InputStream" ||
                                    owner == "org/eclipse/jgit/util/io/SilentFileInputStream"
                            val supported =
                                (name == "readNBytes" && descriptor in setOf("(I)[B", "([BII)I")) ||
                                    (name == "readAllBytes" && descriptor == "()[B")
                            if (opcode == Opcodes.INVOKEVIRTUAL && stream && supported) {
                                changed = true
                                super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "com/helix/jgit/AndroidInputStreams",
                                    name,
                                    "(Ljava/io/InputStream;" + descriptor.substring(1),
                                    false,
                                )
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                            }
                        }
                    }
                }
            },
            0,
        )
        return if (changed) writer.toByteArray() else bytes
    }
}

val hardened = Attribute.of("com.helix.jgit.reject-insecure-tls", Boolean::class.javaObjectType)
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
    attributesSchema.attribute(hardened)
    artifactTypes.getByName("jar").attributes.attribute(hardened, false)
    registerTransform(RejectInsecureJgit::class) {
        parameters.streamSource.set(rootProject.file("config/jgit/AndroidInputStreams.java"))
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
    add("implementation", "org.slf4j:slf4j-api:2.0.18")
    add("implementation", "commons-codec:commons-codec:1.22.1")
}
