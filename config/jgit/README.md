# JGit Android compatibility and TLS correction

The app uses `org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r`, verified
upstream SHA256 `cc63976f92e8058d05a543f320a6237accf2f17b745ab20946f246ac0b54dfd6`.
This upgrades the previous 6.10.0 version. Upstream POM dependencies are
JavaEWAH 1.2.3, slf4j-api 2.0.18 and commons-codec 1.22.1, resolved normally.
The app also enables Google's NIO core-library desugaring 2.1.5.

JGit 7.8 still invokes Java stream methods unavailable on Android API 29.
Standard desugaring provides `transferTo`, but does not supply all of
`readNBytes`/`readAllBytes`. The local artifact transform redirects those exact
virtual calls on `InputStream` and `SilentFileInputStream` to the Helix-authored
`AndroidInputStreams.java` helper, preserving caller-owned streams, EOF, range
validation and IO failure propagation. It does not change Git algorithms or
copy upstream implementation source. Unaffected class bytes remain unchanged.
The helper is an explicit transform input so source changes invalidate its cache.

The existing Helix replacement of
`org.eclipse.jgit.transport.http.NoCheckX509TrustManager` remains fail-closed:
both certificate checks throw `CertificateException`. Normal certificate
verification is not bypassed, and lint findings are not suppressed.

The transform verifies the Maven artifact hash, compiles the two original Helix
helpers with the build JDK, and rewrites only the named TLS class and matching
stream call sites using ASM 9.8. It adds the stream helper and preserves upstream
license/notice entries. Upstream JAR signatures are removed because they cannot
authenticate modified bytes; ZIP ordering/timestamps are deterministic. The
original Maven artifact/cache is untouched. A new upstream checksum requires
review and fresh device tests; version locks support reproducibility, not a ban
on compatible upgrades.

Verification includes helper boundary/IO tests and the actual Git status/stage/
diff device journey on supported APIs. An upgrade is not accepted on compilation
alone. The latest full-suite evidence is recorded in the development status.

Upstream license obligations remain applicable; distribute this correction
notice with the third-party notices and retain the upstream source reference:
https://github.com/eclipse-jgit/jgit/tree/v7.8.0.202609011348-r

This does not authorize new Git operations, remote credentials or automatic
filter/hook execution. Restoring TrustAll requires an explicit new decision.
