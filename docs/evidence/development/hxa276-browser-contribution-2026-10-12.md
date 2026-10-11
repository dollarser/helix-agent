# HXA-276 Browser 单一原生贡献验收

日期：2026-10-12。Owner 本轮授权按 276→279 推进、使用本地模拟器和既有 Qwen、分阶段本地提交。本记录仅证明 HXA-276，未进行真机、第三方账号或长稳测试。

## 实现及边界

`:extensions:browser` 保存标准 manifest、原生贡献及网页任务 Skill，composition root 注入现有 BrowserToolBridge/ToolVisualPreparation。12 个 `browser.*` 工具只有 PluginOrigin 发布；删除旧 built-in 注册及各工具 register 助手。公开工具语义复用原实现，不转换旧执行/审批身份。插件不持有 Activity/WebView，手动页面继续由原宿主负责。

首次发布的新安装以 Browser 为新会话默认选择，用户修改后重新发布不重置默认值，已有会话不追溯改动。目录与管理页使用已有组件/host 投影；选择不拉起页面、不授权网站。作者样例经生产包读取验证，本地导入 native manifest 不创建宿主或发布代码。

## 当前主机验证

全仓 `test detekt`、双渠道 `lintConsumerDebug lintDeveloperDebug`、双渠道 app 与 AndroidTest APK 编译，以及浏览器引擎测试 APK 编译通过。`build/closeout-2026-10-12/host-final-3.log` 记录完整命令成功（1m43s）；像素夹具 viewport 修正后的双渠道 AndroidTest APK 再编译通过（`browser-pixels-build-3.log`）。`scripts/check-all.sh --source` 通过，206 项主机夹具与文档、源码、密钥检查见 `source-final-2.log`。最后一次完整主机复验 `host-final-4.log` 成功（1m8s）；最终 source 检查 `source-final-3.log` 通过，206 项主机夹具、831 篇文档、ADR、i18n 与密钥检查通过。日志中的 mock GUI FAIL_FUNCTIONAL 为主机负例夹具，不是执行过的设备失败。这些是主机结果，不代表设备通过。

## 当前 API36 设备验证

使用 owned runner 的独立只读 `Helix_HXA271_API36` 实例，不借用日常模拟器。所有以下进程已关闭，exit 0；hw.keyboard=yes、硬键盘显示 IME 保持。本轮不重复拼音组成验收，不将其计作输入法通过。

| 制品/范围 | 实际结果 | 证据目录 | App SHA-256 | AndroidTest SHA-256 |
| --- | --- | --- | --- | --- |
| Developer/API36，BrowserContribution、ActivityLifecycle、ToolVisionFlow | 9/9，0 skip，13.39s | `build/hxa276-developer-pixels-final/` | `a44f1c447ae9609188043a8d058782f73ad802253f97060aac906aeb22d4b48a` | `0d1454c4f0a7b69347a8f8b526412496433b8483c6f40b21ea97937102bf4c3d` |
| Standard/consumer/API36，同上 | 9/9，0 skip，14.779s | `build/hxa276-consumer-pixels-final/` | `1445e8c482281a40fad50a7e5e5a3712c49deb0c0313aa5292a8fd0e07c6daa6` | `459ef3633235e1803b70aa366cc1216ab282f71a1b743794813884796ee874c3` |
| 共享 Browser 引擎/API36，Actions/Snapshot/Security/Owner | 29/29，0 skip，51.087s | `build/hxa276-browser-engine-device/` | `0c39961ab2d944f97f44d11b6d3b094e0f7fcf1d7ececc44b722c527a1d76cca` | 同左，自包含 library test APK |

插件夹具通过生产会话选择和正常 Dispatcher：选择后实际 snapshot 成功，撤销后同一工具 TOOL_DISABLED，手动标签仍可导航；重新发布保留用户默认值，关闭 Activity 后 host unavailable，重新选择不创建 host。

新增实际截图夹具使用 Activity-owned WebView 离线 data 页面，通过真实 Browser executor 截图、持久工具步骤、像素披露收据及请求编码；未披露时读取拒绝，披露后独立解码为 1080×2400，红/绿采样为 -65536/-16711936，编码包含实际 input_image。该项没有调用真实视觉模型或外部网站，不把 wire encoding 宣称为视觉模型理解成功。共享引擎 29 项验证实际 DOM 操作、敏感输入拒绝、旧 token、owner 替换及失败路径；只计一次共享实现，不声称每渠道各跑了 29 项。

## 失败尝试与边界

初版截图 fixture 将 WebView 限为 128×128，绿色采样未达到断言；保留 `build/hxa276-developer-pixels/`，改为真实 Activity 全尺寸 viewport 后严格像素/披露断言通过，没有修改生产截图逻辑或删除断言。一次 runner 端口占用拒绝启动，换空闲端口由自有 runner 完成；不把启动失败计成功。

本阶段没有真实模型请求，也没有覆盖 Mobile Use 实后端、在途共享资源释放或新增 tabs/wait；这些分别属于 HXA-277/278/279。日常 APK 仍为上一阶段验收的 HXA-275，保留数据更新详见[收尾证据](plugin-closeout-2026-10-12.md)。没有发布、推送、卸载或清数据。
