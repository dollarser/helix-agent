# Alpine Linux RootFS — 许可证与来源说明

本文件是嵌入 RootFS 存档（`runtime/rootfs/alpine-minirootfs-3.22.5-aarch64.tar`）的
许可证再分发说明（`runtime-lock.json` 中 `alpine-rootfs` 组件的 `license.textRef`）。
PRoot 侧组件（proot/loader/libtalloc/libandroid-shmem）的许可证文本在各自引用的
`licenses/*.txt` 文件中，不在本文件范围。

## 来源（provenance）

- **基础镜像**：Alpine Linux 3.22.5 minirootfs（aarch64），官方发布
  <https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.5-aarch64.tar.gz>
  （SHA-256 `3fbc6285032ed46821b511292633d7b2a6306a2e254f590e92bdafff56cf2f70`，3,966,256 字节）。
- **构建镜像**：Docker Hub `alpine:3.22.5`，digest 锁定
  `sha256:14358309a308569c32bdc37e2e0e9694be33a9d99e68afb0f5ff33cc1f695dce`
  （`scripts/build-proot-assets.sh` 按 digest fail-closed 校验）。
- **包仓库**：Alpine v3.22 官方仓库（main + community）。每个 `.apk` 在构建时由镜像内置的
  `alpine-keys`（`alpine-keys-2.5-r0`）做签名验证；构建脚本可按 `ALPINE_MIRROR` 走镜像加速，
  但完整性与镜像无关——包签名与最终存档 SHA-256（记录于 `runtime-lock.json`，唯一版本真相）
  均独立于传输渠道。
- **包版本**：下表 53 个包的版本全部固定（pinned）在 `runtime-lock.json` 的
  `packages[]` 中；构建时 `apk add name=version` 精确安装，任何上游新版本都不会静默进入。
- **最终存档**：构建产物经 `scripts/deterministic_tar.py` 重打包（条目按路径排序、
  uid/gid=0、固定 mtime=2026-01-01 UTC、GNU 格式），得到一个字节级可复现的 **原始
  tar**（`alpine-minirootfs-3.22.5-aarch64.tar`）。**锁定对象是这个原始 tar**：它的
  SHA-256 与大小记录于 `runtime-lock.json`，设备端安装器（HXA-082）安装前对实际读到的
  字节重算比对。之所以锁定原始 tar 而非 gzip 包，是因为 Android Gradle 在打包 APK 时
  自动展开 `.gz` 资源，设备从 APK 资产读到的正是原始 tar 字节；锁定"设备真正读到的
  字节"才能保证校验闭环。gzip -9 mtime=0 的 `.tar.gz` 仍作为可复现构建产物生成，其
  SHA-256 仅记录于构建日志，不是锁定值。
- 存档内含 `/lib/apk/db/installed`（apk 数据库，含每包签名校验和），供离线审计。

## 已安装包清单（53 个，版本 + 许可证）

| 包 | 版本 | SPDX 许可证（apk 元数据） |
| --- | --- | --- |
| ada-libs | 2.9.2-r4 | `( Apache-2.0 OR MIT ) AND MPL-2.0` |
| alpine-baselayout | 3.7.0-r0 | `GPL-2.0-only` |
| alpine-baselayout-data | 3.7.0-r0 | `GPL-2.0-only` |
| alpine-keys | 2.5-r0 | `MIT` |
| alpine-release | 3.22.5-r0 | `MIT` |
| apk-tools | 2.14.10-r0 | `GPL-2.0-only` |
| bash | 5.2.37-r0 | `GPL-3.0-or-later` |
| brotli-libs | 1.1.0-r2 | `MIT` |
| busybox | 1.37.0-r20 | `GPL-2.0-only` |
| busybox-binsh | 1.37.0-r20 | `GPL-2.0-only` |
| c-ares | 1.34.8-r0 | `MIT` |
| ca-certificates | 20260611-r0 | `MPL-2.0 AND MIT` |
| ca-certificates-bundle | 20260611-r0 | `MPL-2.0 AND MIT` |
| gdbm | 1.24-r0 | `GPL-3.0-or-later` |
| git | 2.49.1-r0 | `GPL-2.0-only` |
| git-init-template | 2.49.1-r0 | `GPL-2.0-only` |
| icu-data-en | 76.1-r1 | `ICU` |
| icu-libs | 76.1-r1 | `ICU` |
| libapk2 | 2.14.10-r0 | `GPL-2.0-only` |
| libbz2 | 1.0.8-r6 | `bzip2-1.0.6` |
| libcrypto3 | 3.5.7-r0 | `Apache-2.0` |
| libcurl | 8.14.1-r3 | `curl` |
| libexpat | 2.8.4-r0 | `MIT` |
| libffi | 3.4.8-r0 | `MIT` |
| libgcc | 14.2.0-r6 | `GPL-2.0-or-later AND LGPL-2.1-or-later` |
| libidn2 | 2.3.7-r0 | `GPL-2.0-or-later OR LGPL-3.0-or-later` |
| libncursesw | 6.5_p20250503-r0 | `X11` |
| libpanelw | 6.5_p20250503-r0 | `X11` |
| libpsl | 0.21.5-r3 | `MIT` |
| libssl3 | 3.5.7-r0 | `Apache-2.0` |
| libstdc++ | 14.2.0-r6 | `GPL-2.0-or-later AND LGPL-2.1-or-later` |
| libunistring | 1.3-r0 | `GPL-2.0-or-later OR LGPL-3.0-or-later` |
| mpdecimal | 4.0.1-r0 | `BSD-2-Clause` |
| musl | 1.2.5-r12 | `MIT` |
| musl-utils | 1.2.5-r12 | `MIT AND BSD-2-Clause AND GPL-2.0-or-later` |
| ncurses-terminfo-base | 6.5_p20250503-r0 | `X11` |
| nghttp2-libs | 1.69.0-r0 | `MIT` |
| nodejs | 22.23.2-r0 | `MIT` |
| pcre2 | 10.46-r0 | `BSD-3-Clause` |
| pyc | 3.12.14-r0 | `PSF-2.0` |
| python3 | 3.12.14-r0 | `PSF-2.0` |
| python3-pyc | 3.12.14-r0 | `PSF-2.0` |
| python3-pycache-pyc0 | 3.12.14-r0 | `PSF-2.0` |
| readline | 8.2.13-r1 | `GPL-3.0-or-later` |
| ripgrep | 14.1.1-r0 | `MIT OR Unlicense` |
| scanelf | 1.3.8-r1 | `GPL-2.0-only` |
| simdjson | 3.12.0-r0 | `Apache-2.0 OR MIT` |
| simdutf | 7.2.1-r0 | `Apache-2.0 OR MIT` |
| sqlite-libs | 3.49.2-r1 | `blessing` |
| ssl_client | 1.37.0-r20 | `GPL-2.0-only` |
| xz-libs | 5.8.3-r0 | `GPL-2.0-or-later AND 0BSD AND Public-Domain AND LGPL-2.1-or-later` |
| zlib | 1.3.2-r0 | `Zlib` |
| zstd-libs | 1.5.7-r0 | `BSD-3-Clause OR GPL-2.0-or-later` |

## 许可证义务

- 上述二进制包均为 **Alpine 官方仓库的未修改再分发**（`.apk` 原样安装，未重编译、未改包）。
  对应源码按 Alpine 惯例在 aports 仓库可得：
  <https://gitlab.alpinelinux.org/alpine/aports>（`main/<包名>` 或 `community/<包名>`）。
- GPL 家族包（bash、readline、git、busybox、apk-tools、gdbm、musl-utils、xz-libs、libgcc、
  libstdc++ 等）随本存档分发时附带本说明与上表（许可证对应关系）；应要求提供对应源码时，
  指向 aports 对应包目录（含上游 tarball 与 Alpine 补丁）即满足 GPL 第 6 条的源码提供义务。
  本产品不修改这些包，故不产生"衍生作品"的额外义务。
- `blessing`（sqlite-libs）：SQLite 处于公共领域，"blessing" 为其官方许可标记。
- `ICU`（icu-libs/icu-data-en）：ICU 许可证（MIT 变体），文本见 ICU 官方
  <https://www.unicode.org/copyright.html>。
- `curl`（libcurl）：curl MIT 许可证（libcurl 使用 MIT 风格许可，`curl` 为其 apk 元数据标记）。
- `bzip2-1.0.6`（libbz2）：bzip2 许可证（1.0.6 及更早版本的专有许可，允许再分发）。
- 完整许可证文本（GPL-2.0/GPL-3.0/LGPL-3.0/BSD-3-Clause 等）随 `licenses/` 目录分发；
  包级 SPDX 表达式以上表为准（源自 apk 官方元数据 `L:` 字段）。

## 运行边界

该 RootFS 仅在 PRoot 用户态根内运行（HXA-084），作业内网络默认关闭（禁止网络包安装）；
`apk` 数据库仅用于离线审计与包清单展示，不是安装通道。
