# Termux runtime sources and notices

Helix uses unmodified payloads from PRoot 5.1.107.92, talloc 2.4.3 and
libandroid-shmem 0.7 (aarch64). Binary sizes and SHA-256 values are pinned in
`runtime-lock.json`; download locations do not replace integrity checks.

The [fixed resource release](https://github.com/dollarser/helix-agent/releases/tag/runtime-assets-20260917)
provides the original Debian packages and adjacent materials:

- [Corresponding source and build scripts](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/termux-runtime-corresponding-source.tar.gz)
- [Licenses and original copyright notices](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/termux-runtime-licenses-notices.tar.gz)
- [Binary/source/build-revision manifest](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/termux-runtime-source-manifest.json)
- [Build instructions and verification boundaries](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/termux-runtime-README.md)
- [SHA-256 checksums](https://github.com/dollarser/helix-agent/releases/download/runtime-assets-20260917/termux-runtime-SHA256SUMS)

PRoot is GPL-2.0-or-later, copyright STMicroelectronics and contributors;
its original COPYING and complete source include both loaders. The Termux
`termux-chroot` script, package recipes, common build scripts and NDK patches
are included at the corresponding revisions. No Helix source patch is applied.

The talloc library is LGPL-3.0-or-later, incorporating GPL-3.0 terms;
copyright Andrew Tridgell and Stefan Metzmacher. Original source notices and
both license texts are retained. The Termux recipe separately labels its
package GPL-3.0; this does not relicense the library or every included file.

libandroid-shmem is BSD-3-Clause, copyright 2013 Sergii Pylypenko and 2017
Fredrik Fornwall. Its complete notice/disclaimer is in `BSD-3-Clause.txt` and
the source/notice archives. Termux package scripts follow their package
licenses; common infrastructure follows upstream Apache-2.0 and any retained
file-specific notices.

These archives preserve the source and build materials; they are not a claim
of a bit-identical rebuild, device validation or a complete Alpine/Helix
license audit. The release's original `runtime-lock.json` remains its historical
snapshot; current builds use the lock in their own source checkout. RootFS
provenance and licenses remain in `ALPINE-README.md`.
