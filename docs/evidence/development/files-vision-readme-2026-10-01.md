# README 双语、文件工具回填与视觉验证

日期：2026-10-01。工作树定向验证，未提交/推送；不是全量产品或发布验收。

## 问题与实现

- README.md 保持默认中文；README.en.md 提供对应英文，顶部相互导航。两版保留开发预览、下载、数据库不兼容清空记录及升级前导出提醒。相对链接检查通过；本轮未重新发布 APK 或核验远端 release 资产。
- 最近两次用户报告的 files.list：工具 durable 状态 COMPLETED，结果 SUCCEEDED 且 verified=1；所属 Turn 在下一次模型请求阶段 FAILED/PROTOCOL。日志 START 后 EXCEPTION、未到 ENCODED，确认是本地编码而非列目录权限或远端账号封控。
- 私有原始参数的 path 为相对路径，Harness 已按会话 Workspace 绑定为 scoped path。Antigravity 回放比较原始与 canonical 业务参数，错误拒绝合法规范化。
- 修复保持原始私有记录完整性、账号/模型/调用身份及名称检查；协议签名保留，function args 使用 Harness 的 canonical business args，剥离展示字段。适配器不重新决定 Workspace/权限。这是共享回放修复，覆盖同一路径规范化链路，不能据此宣称每个工具均已实测。
- RealToolVisionDeviceTest 支持显式指定现有 provider/model，默认仍用本地 SGLang；增加 files.list 前置步骤、合成浏览器截图场景及真实像素结果断言。只批准测试 tab 的截图和测试会话图片披露，不授予全局权限。临时 SGLang provider 与测试 tab 在 finally 清理；合成会话可保留用于诊断。

## 主机

- runtime:cli-app Debug unit：通过，AntigravityProtocolTest 12/12；包含进程重开后的 Workspace canonical args、展示字段剥离、账号/模型/调用身份隔离。
- tools:files/test、tools:browser/test、app Developer lint：通过。
- Developer APK 与 AndroidTest APK 编译、spotlessCheck、detekt：通过。
- check-all.sh --source、git diff --check、两版 README 相对链接：通过。
- 日志：build/files-vision-host-r2.log（unit 成功，但夹具静态检查失败，已修复）、build/files-vision-host-r3.log、build/files-vision-final-host.log、build/files-vision-source-gate.log。

## API36 Developer / arm64

所有者本轮明确授权现有 emulator-5566 与已配置模型有界测试。覆盖安装保留账号和用户数据，未卸载/清库。合成图片及页面不含用户文件或浏览内容。

| 验证 | 实际结果 |
| --- | --- |
| Antigravity gemini-3.8-flash-tiered | 1/1，25.893s。files.list COMPLETED → view_image COMPLETED → Turn COMPLETED；1 个 TOOL_OBSERVATION 图片，回答 LEFT=red;RIGHT=green |
| SGLang Qwen3.8-27B / OpenAI Chat | 1/1，13.084s。tools.search → browser.screenshot COMPLETED → Turn COMPLETED；1 个 TOOL_OBSERVATION 图片，回答 LEFT=red;RIGHT=green |
| ToolVisionFlowDeviceTest | 3/3，0.274s。真实像素/存储/请求编码及同意门、无效来源拒绝、无效绑定回滚；不调用模型 |

SGLang 主机 /v1/models 可达，模拟器使用 10.0.2.2:30008/v1。截图场景使用真实已附着 WebView，页面只有左右红/绿色块；不使用 DOM 颜色文字作为答案输入。日志和合成输出：build/files-vision-antigravity-device.txt、build/files-vision-antigravity-result.json、build/browser-vision-sglang-device.txt、build/browser-vision-sglang-result.json、build/tool-vision-offline-device.txt。

边界：仅这两个精确模型和上述场景；不等于所有 Provider、文件工具、网站、真实手机或全量视觉任务通过。模型可先搜索未直接暴露的 browser.screenshot，本轮实际完成正常，不以一次额外工具调用认定循环。
