# 订阅账号连接检查与设置／审计排版

日期：2026-09-10。范围：HXA-190/191，所有者要求只做本地主机验证，设备验收留待后续。

## 连接测试为什么使用 gpt-6-astra

Subscriptions 的旧入口通过 `CodexSmokeCatalog.read` 取得模型目录第一个可见 slug，再发起固定文本生成；这是服务端目录顺序，不是随机选取，也不是按价格排序。Helix 主应用旧连接检查使用 `config.model`，即当前配置的模型。这两种策略都不适合独立验证订阅账号连接。

按所有者明确的订阅级检查要求，本轮采用 [ADR-0046](../adr/0046-subscription-account-connection-check.md)：

- Subscriptions 入口更名为“测试 Codex 订阅连接”，只请求真实认证目录；认证失败沿用一次刷新，仍失败则报告，不发 POST 生成请求。
- Helix 的 Codex 连接检查使用同一类真实认证目录证据，和当前模型选择解耦。配置模型不在返回目录中，不影响账号连接检查；这不代表该模型可用。
- 不猜测模型价格、不固定“便宜模型”名单；连接检查无生成用量。目录可用不保证剩余额度、具体模型或全部推理能力可用，后者仍由显式能力检测和实际对话验证。
- 只有真实认证目录适配器可以走此路径。其他没有认证目录的适配器及静态模型列表不能直接判成功；普通 API Provider 保留原连接请求。旧固定生成 helper 留作显式协议诊断及回归，不再由 Codex 日常连接按钮调用。
- 认证、DNS、IPC、模型选项、会话内容及能力检测请求不因本轮优化改变。

## 排版调整

- PRoot 验证／修复／许可证与来源／移除按钮使用可换行操作区；Root、无障碍和出口规则的多按钮区同样处理，避免后面的按钮被挤成逐字竖排。
- 设置中的 Runtime、Root、无障碍、语言、Provider 与预算按圆角背景分组；删除与顶部重复的页面标题，保留所有功能入口。
- 审计会话／工具／风险筛选与日期输入随可用宽度换行，长选项摘要单行省略，菜单仍可查看选项；不再把三个固定宽度选择器硬塞进窄屏横排。
- 审计条目改为独立卡片，默认显示结果／工具／风险摘要和本地时间；会话、Turn、关联 ID 与原有详细信息可展开。记录查询、过滤范围和原脱敏白名单保持不变。
- 顺带修正此前 DNS 默认域名硬编码引起的 lint 国际化错误，默认值本身不变。

## 主机验证

`verify-account-connection-layout.py` 执行定向格式化、以下回归及构建：

- `:runtime:cli-app:testDebugUnitTest --tests '*CodexSmoke*'`
- `:app:testDeveloperDebugUnitTest --tests '*ProviderConnectionCheckTest' --tests '*CodexCapabilityProbeTest'`
- `:app:assembleDeveloperDebug :app:assembleConsumerDebug :runtime:cli-app:assembleDebug`

33 用例通过，0 失败、0 跳过。覆盖没有生成请求、配置模型独立、认证拒绝、不支持账号检查不能假通过、401 单次刷新，以及原能力检测仍实际验证模型。

额外执行 `:app:lintDeveloperDebug :app:lintConsumerDebug :runtime:cli-app:lintDebug`，全部通过；国际化、ADR（46 条）、文档（387 个 Markdown）及 diff 检查通过。最终两种 app flavor 与 Subscriptions APK 已重新构建。

脚本保存在 `scripts/debug/2026-09-10/`，机器输出位于忽略的 `build/debug/2026-09-10/account-connection-layout/`。没有连接真机或启动／借用模拟器，没有安装设备，也没有提交推送。

## 下一次人工验收

1. Subscriptions 点击新连接入口：显示认证与目录通过，不再显示测试某个模型或固定生成文本。
2. Helix 更换当前聊天模型后测试 Codex 连接：保持当前选择，不触发生成；能力检测仍是独立操作。
3. 设置正常字号及较大字号下检查许可证、Root、无障碍按钮：放不下时整颗按钮换到下一行；各按钮仍可点击。
4. 审计检查长会话名、工具／风险／日期筛选及清除；展开详情应可查看原标识，收起后只保留摘要。

实际字体、系统缩放及真机视觉效果尚待此轮人工验收，不以主机编译通过替代视觉确认。
