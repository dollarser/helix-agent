# Locked JGit TLS correction

The app applies `reject-insecure-tls.gradle.kts` to JGit
`org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r`, original SHA256
`43f92f3adb681a5f3006b979e8d341c12a8cfd8029f287c42bcf0a80377565ae`.

The Helix-authored replacement preserves the binary class and method signatures of
`org.eclipse.jgit.transport.http.NoCheckX509TrustManager`, but both certificate
checks throw `CertificateException`. Calls asking to disable TLS verification fail;
normal trust-manager selection and local status/diff code are unchanged. This is
not a dependency upgrade, lint suppression, or assertion that unsafe code is unreachable.

A Gradle artifact transform compiles only this replacement with the build JDK,
checks the original artifact checksum, and changes only that class. Other jar entries,
including upstream license/notice files, retain their original bytes. Upstream jar
signature files are removed because they cannot authenticate a locally changed jar.
The original verified Maven artifact/cache is untouched. ZIP ordering/timestamps
are normalized. Any upstream checksum change fails the build pending review.

Only the JGit jar is resolved through the locked non-transitive `helixJgit`
configuration. Its three pinned upstream POM dependencies remain ordinary app
dependencies and participate in the existing version conflict resolution. This
avoids injecting duplicate file jars or changing AGP's own classpath attributes.
The app lockfile changes only JGit's configuration membership; no resolved
dependency version is upgraded.

JGit's upstream licensing remains applicable to the distributed library; this
replacement source is original Helix code. Distribute this correction description
with the project's third-party notices and retain the original upstream source link:
https://github.com/eclipse-jgit/jgit/tree/v6.10.0.202406032230-r

Mechanism reference: https://docs.gradle.org/current/userguide/artifact_transforms.html

This does not authorize remote Git, filter execution, credential helpers, or changes
to the accepted Git product scope. A future legitimate use of `http.sslVerify=false`
requires an explicit new decision; it must not restore TrustAll silently.
