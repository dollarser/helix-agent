# ADR-PLATFORM-002: 浏览器 View 与逻辑标签生命周期

Status: accepted
Date: 2026-09-16
HXA: HXA-160
Deciders: Project owner（当前有效决定；授权按需求合并重编，不新增功能接受范围）

## Context

WebView 必须由正确的 Activity 生命周期持有，逻辑标签与页面实例不能混为一个跨 Activity 单例。

## Decision

Activity 持有 BrowserViewOwner，使用真实 Activity Context 惰性创建宿主；BrowserController 保留逻辑标签和当前 owner 的弱绑定。替换 owner 使旧绑定失效，迟到解绑不能销毁新 owner。所有旧页回调校验 owner/宿主身份。

后台切换不销毁 View；pause/resume 作用于匹配 owner，不把 onPause 当作 JS 全局暂停。仅当前活动标签、可用前台窗口显示 JS 对话框；切后台、导航、关闭、解绑或取消时终结结果一次。保留平台 Autofill 能力并以真实服务 fixture 验证。

无宿主时非空 open 返回明确失败且不新建标签，navigate 返回稳定宿主不可用原因；历史/重载缺少真实页面时不假报执行成功。空标签仍可独立存在。页面销毁清理 loading/history 可用性与 DOM token，保留 URL/标题供显式导航；不自动加载 URL、重放 POST、表单或工具。恢复后由原任务正常继续路径决定下一步，不自动拉起 Activity、不改变 Goal 预算或批准契约。

## Alternatives considered

- Application Context 继续使用：保留现状但缺少完整窗口功能。
- MutableContextWrapper：不足以保证旧 View 内部服务/窗口全部重新绑定。
- 独立进程/内核：扩大运行时与 IPC 成本，尚无证据可消除系统 Binder 缺陷。
- WebView 池/预热/configChanges 绕过：增加生命周期复杂度，暂不采用。

## Consequences

同一主题使用一份有效契约，避免并行实现各自解释权限和生命周期。代价是实现、UI、数据与恢复需要一起验证；accepted 表示决定，不代表相关任务全部完成。

## Verification

本次为现行决策整理，不新增功能通过结论。实现范围与实际命令结果以[实施状态](../../development/status.md)、对应 HXA 及完成记录为准；修改本契约后须覆盖成功、失败、取消、边界和恢复，不能用文档门禁代替设备/功能验收。

## Reconsider when

产品所需能力超出本决定边界，或平台、依赖、资源和设备证据证明当前方案不可行时重新评审；普通实现修复不另造一套决策。

## References

- [实施状态](../../development/status.md)
- [开发路线](../../development/roadmap.md)
- [主题入口](README.md)
