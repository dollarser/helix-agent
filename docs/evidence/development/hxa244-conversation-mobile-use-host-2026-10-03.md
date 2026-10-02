# HXA-244：Conversation 持久 Mobile Use 授权与应用选择验收

日期：2026-10-03。基线 `aa596b0c9fef17892f1d0621a2e6a0172c9d37bd`，在 main 收尾。所有者已授权本地提交，不推送；本轮未请求模拟器、真机或真实外部账号，不继承 HXA-241 的设备结果。

## 交付结论

用户/系统应用选择器、Conversation 级持久授权、运行态重建和原生截图共享已经接入。当前完成主机与 Debug 制品验收；不是设备、发布版或商店验收。此前 HXA-243 的临时许可和切换接收方确认初稿由本轮契约替代，工具执行和图片来源校验仍走既有管线。

### 应用选择与会话配置

- `AutomationApplicationPicker` 提供全部、用户应用、系统应用、已选四类筛选，支持名称/包名搜索、多选、刷新、滚条和取消保留。包名用于显示身份，不再是主要输入方式。
- `AutomationApplicationCatalog` 使用实际安装应用信息分类；无 launcher、已禁用或当前不可见的已选应用有明确状态。刷新和搜索不丢失原有选择。仅 Advanced 声明应用列表查询权限，Standard 不引入该权限或 Mobile Use 服务。
- 选择器确认只更新表单；“保存并开启”才写入当前 Conversation。系统能力未就绪或尚未选择模型时也可以先保存，不假装已经具备执行条件。
- `MobileUseGrantStore` 使用同步设置存储，按 Conversation ID 保存完整应用集合、范围身份和屏幕共享配置。无 TTL 或动作数配额。长集合使用规范化全值摘要作为有界审计引用，完整集合仍用于真实授权；1,000 个包名的保存/重建有主机回归。

### 授权持久，运行态可重建

授权不依赖锁屏、进程、服务或当前模型的暂时状态。熄屏/锁屏、服务断开或系统权限丢失、进程/设备重启只使操作暂时不可执行，不删除 Conversation 配置。条件恢复后，下一次获准工具调用可重新建立运行态；不自动重放中断动作，不恢复旧节点/frame。

`ChatDispatchRequests` 使用原调用的 Conversation 取得 Scope，Dispatcher 将批准的 `authorizationScopeRef` 传给 executor。执行前再次核对保存范围；B 会话不能借用 A 会话授权。范围修改生成新 grant 身份；不变的保存复用原身份，不额外制造确认。

用户关闭会话中的 Mobile Use 或点击绑定该授权的通知停止按钮时，才撤销持久配置。旧通知不能误关后来运行的其他 Conversation 或新范围。保存/关闭失败明确报错，当前进程停止使用未确认配置；不能把写入失败说成已经持久撤销。

### 原生截图不逐图确认

保存 Mobile Use 时，界面说明截图可供本会话当前及以后用户选择的模型处理，并显示接收 Provider、模型和网络 origin。后续原生截图、换 Turn 或用户切换模型不再创建 Mobile Use 图片确认卡；这不等于绕过会话的工具权限。

`WorkspaceToolImagePublisher` 和 `MobileUseScreenConsent` 持久登记采集 Scope、Conversation、Turn、哈希和媒体类型。每次请求自动建立与实际图片/接收方一致的校验凭据，最终读取字节时由 `BoundImageAccess` 再检查归属、活动 Turn、视觉能力、哈希和接收方。凭据重建不是新的用户审批；旧范围不能给旧截图重新贴新授权。

真实 OnDeviceLocal 模型不走远端图片确认，仍验证图片来源和 Mobile Use 会话配置。没有视觉能力时保留图片产物并说明没有回填像素；普通附件、浏览器截图或文件读图不会借用 Mobile Use 的原生截图共享规则。图片文件保留/删除沿用现有会话产物机制，已外发数据不声称可以撤回。

会话工具模式保持原义：FULL_ACCESS 不额外增加 Mobile Use 逐动作确认，APPROVAL_REQUIRED/READ_ONLY/CUSTOM 和工具禁用仍按用户设置执行。Android 自身权限、受保护窗口及真实物理资源边界不变。

## 本轮收尾验证

重新执行 `bash scripts/debug/2026-10-03/validate-hxa244-host.sh`，最终目录为 `build/hxa244/host-20261003-024440-8727/`：

```text
BUILD SUCCESSFUL
1186 actionable tasks: 39 executed, 1147 up-to-date
```

覆盖根单测、detekt、Spotless、全模块 Debug lint、两渠道 App lint、两渠道 App/AndroidTest APK，以及 automation 模块 AndroidTest APK 构建。38 个依赖锁文件检查通过。该结果属于增量验收，未声称所有测试强制重跑。

重点汇总为 227 个不同方法、264 次跨渠道结果，0 失败、0 跳过；包含已有及本轮新增/修订回归，不称为新增 227 项。其中持久授权 7 项、应用选择投影 7 项、Conversation 运行态 4 项、原生屏幕共享 9 项分别有逐用例结果；另外覆盖 Dispatcher 原始范围传递、普通图片规则与产物发布。

App 完整报告：Consumer 1,081 项（1,077 通过、4 跳过），Developer 1,178 项（1,174 通过、4 跳过）。四项既有跳过依赖额外外部 Connector 服务或归档材料，名称保存在机器汇总中；跨渠道共享方法不重复视为独立场景。Mobile Use 的 Python 制品规则 6 项通过。

文档回填后重新执行 `scripts/check-all.sh --source`、实际 APK 的 `--artifacts` 和 `git diff --check`。实际制品核对网络 XML、无障碍服务/原生能力声明、Advanced 应用列表查询权限、Standard 排除，以及原有订阅/PRoot/FFmpeg 渠道边界；APK 检查不代替设备运行。

## 验收记录校正与复核入口

此前 `host-acceptance.json` 仍指向 02:30 构建，且 Scope 标签错误写成单个 lint task；当时 `EvaluationMetadata.kt` 与高级版 AndroidTest APK 已更新。旧 JSON 保留为 `build/hxa244/host-acceptance-before-closeout.json`，不把过时文件当成最终通过证明。

当前使用修正后的汇总入口，将最终主机日志、文档回填后的源码检查、制品检查、测试 XML、当前变化源码和五份 APK 重新绑定：

```bash
python3 scripts/debug/2026-10-03/summarize-hxa243-host.py \
  --conversation-grants \
  --host-run build/hxa244/host-20261003-024440-8727 \
  --source-log build/hxa244/source-closeout.log \
  --artifact-log build/hxa244/artifacts-closeout.log \
  --mobile-use-log build/hxa244/mobile-use-closeout.log \
  --output build/hxa244/host-acceptance.json

python3 scripts/debug/2026-10-03/summarize-hxa243-host.py \
  --verify-inputs --output build/hxa244/host-acceptance.json
```

`--verify-inputs` 在提交前后核对记录的源码与全部五份 APK；不重新盖章、不执行设备、不覆盖 HXA-243 历史验收。Git 状态以本次提交及其检查结果为准。提交只包含本任务源码、测试、文档及必要复验脚本；APK、原始日志、其他历史一次性调试文件不提交。

## 验收边界

设备状态 **not requested**。持久化/临时中止/服务恢复/跨会话隔离目前由主机存储及运行态回归验证，实际重启、锁屏/解锁、Android 服务重连和多 Conversation 的第三方 App 操作仍需要单独设备验收。新增应用选择器和视觉/授权 AndroidTest 仅完成编译，不能称为实机交互通过。

本轮未使用真实外部账户/额度，也未完成 Release 或商店发布验收。截图后端与保护窗口边界仍遵守 HXA-243 实现；持久授权不会让 Android 不支持的窗口、锁屏认证或其他用户资料夹变得可操作。
