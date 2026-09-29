# P3 本地模型安装最小闭环（2026-09-28）

本记录对应 `docs/development/release-readiness.md` 的 P3。范围是在 HXA-222 已交付的本地 Provider / Binder / llama.cpp / 私有资产基础上补安装产品层，不重开 AgentLoop、TurnEngine、Dispatcher、permission/effect owner，也不增加 GitHub device CI、真实账号或发行工作。

## 产品合同

- Models → On-device 默认进入精选目录；保留“高级导入”供精确 HTTPS URL + SHA-256 + byte size。
- 精选条目不是 display-name trust：资产 identity 仍是 exact bytes / SHA-256。目录固定 repository、40-char revision、filename、size、SHA、quantization、license、tested context 与证据级别。
- 当前条目：
  - `Qwen3-0.6B-Q4_K_M.gguf`，396,705,472 bytes，SHA `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`，Apache-2.0；ModelScope `6091bc857fe0dffa19c581a7ccc7def1b126ff54`，Hugging Face `f2d6f9ca53a254cc379437c49e4b2eb447f779df`；仅标 compatibility evidence。
  - `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`，2,497,281,120 bytes，SHA `3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597`，Apache-2.0；ModelScope `0b0406b39725d752255ffeb48f102c66f98e14aa`，Hugging Face `18727206c51467496bfba014368bd0a30e97f411`；只声明 HXA-222 fixed-task evidence，不外推手机推荐。
- 生产手动 URL 必须 HTTPS 且不重定向。精选 URL 可跟随最多 4 次重定向，但目标仍须 HTTPS 且 hostname 属于该来源允许的 host suffix；不会把 `modelscope.cn.evil.example` 等前缀伪装域视为可信。
- 安装前执行 `ModelAssetStore.requireCanPublish()` 与 `StorageManager.getAllocatableBytes()`。空间预算为剩余 transfer + 一份完整 verified publish + 16 MiB reserve，匹配 cache partial 与 model-store `.part` 同时存在的真实峰值。
- 已有 partial 请求只有精确 `206 Content-Range(offset,total)` 才 append；`200` 重新覆盖。错误 range/hash/GGUF/完整坏 partial fail closed 并删除不安全 partial。Cancellation/普通 IO 中断不被误当 corruption，保留 partial 供相同 hash/size 恢复。已校验重复安装直接复用资产，不再联网。
- 原子发布成功后写入 ON_DEVICE_ASSET / ON_DEVICE_LOCAL Provider，再立即执行既有 connection probe；连接通过才继续 capability probe。probe 失败不删除 verified asset。
- 安装从不自动切 Session。只有用户明确点击“用于当前会话”才调用现有 `ChatService.selectSessionModel`，只影响当前会话 future turns，运行中 turn gate 仍生效，其它 Session 不变。

## Host 验证

新增 `LocalModelDownloaderTest` 与 catalog/asset tests 覆盖：

- fresh `200` 下载 + atomic publish；
- 已有 partial 对 `200` restart，不 append；
- cancellation 保留 partial，后续精确 `206` resume；
- 错位 `Content-Range` 删除 unsafe partial；
- hash mismatch 不发布；完整但 digest-invalid partial 被清理且不发网络；
- disk preflight 在打开连接前拒绝；
- verified duplicate install 不发第二次网络请求；
- quota/count preflight 与 publish admission 一致；
- curated revisions / HTTPS source / redirect host-family contract。

相关 local-model unit + `:provider:api:test`、Spotless、detekt 均通过。

## API36 developer Android HTTP E2E

测试脚本：`scripts/debug/2026-09-28/run-p3-local-model-install.py`。脚本不下载权重；只接受工作区已保留并再次校验 size/SHA 的 HXA-222 0.6B GGUF，在 host `127.0.0.1` 启动支持 Range 的受控 server，再由 owned emulator runner 使用 `adb reverse` 暴露到 Android loopback。生产仍不允许 cleartext；AndroidTest 只通过 internal test seam 进入同一 downloader/install/probe orchestration。

最终源码对应输出：`build/p3-local-model-install/api36-developer-r4/`。

- AVD：`Helix_HXA210_API36`，API 36，arm64-v8a，4 GiB，4 cores，density 400。
- developer app APK SHA-256：`419dd9b2636a86ae340f80bc9ab2012cabbe60c9d388c0277e69ba8c9b91b6ec`。
- developer AndroidTest APK SHA-256：`c6c5796ff60d85ffdc3ea39e3661416733c015ad047ac18fe8e41e3be232ac99`。
- 首请求：无 Range，HTTP 200；外层安装 coroutine 在下载约 4.8 MiB 后真实 `cancelAndJoin()`，验证 UI/调用方 cancellation 可穿透 ProviderService 编排并停止 transfer。
- 恢复请求：`Range: bytes=4808704-`，fixture 返回 HTTP 206 / matching Content-Range。
- 后续真实 asset SHA/GGUF publish、Provider 注册、Binder/JNI connection probe、capability probe、当前 Session 显式选择均通过；另一 Session 仍未绑定。
- instrumentation：`OK (1 test)`，runner `closed.json` exit 0；结束后 `adb devices` 为空。

UI / Session 回归：最终同一 APK/Test APK 下，`LocalModelDialogDeviceTest` + `SessionModelDeviceTest` 在 API36 developer 3/3 passed，证明精选目录默认可见、高级导入失败仍保留 draft、既有 Session model selection 持久化与 active-turn 拒绝切换合同未回归。

## 工程总门禁

最终生产改动后执行 `./scripts/check-all.sh --all`：`BUILD SUCCESSFUL`，1313 actionable tasks（83 executed / 1230 up-to-date）。dependency lock verification 36 files passed；consumer/developer debug/release APK component/process/UID/payload/launcher boundary、variant boundary、subscription managed-account / Runtime boundary / consumer exclusion / normal chat path checks均 passed。

中途该 gate 曾因新增空间检查直接使用 `File.usableSpace` 触发 Android Lint `UsableSpace`；没有 suppress/baseline，而是改为平台 `StorageManager.getAllocatableBytes()` 后重新通过。强模型 diff 复核又发现 `ProviderService.install…` 若直接继承 service scope 的 Job，可能削弱 UI caller cancellation；最终实现改为仅借用 service dispatcher、保留调用方 Job，并由 r4 设备 E2E 的真实 `cancelAndJoin()` 验证。其它中途失败均为编译/API/格式测试夹具修正，不是设备产品链路失败。

## 未覆盖 / 不外推

- 没有从公网实际下载几百 MB/GB 权重；公网 URL/revision 由固定元数据和 host/source policy 管理，真实 Android byte-path 由受控 HTTP fixture 证明。
- 没有物理手机、OEM、热压、Doze、低存储真实清理、蜂窝网络或 CDN 长距离下载数据。
- 4B 一次 fixed task 通过仍不代表普遍可靠或性能推荐；0.6B compatibility 也不表示适合真实 Agent 工作。
- P3 不实现 Project Memory、Subagent、GPU/NPU 或新的 Agent runtime；下一 checkpoint 是 P4 首次成功联合旅程。
