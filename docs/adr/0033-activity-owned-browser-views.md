# ADR-0033: Activity 持有浏览器 View 与逻辑标签分离

Status: accepted
Date: 2026-09-08
HXA: HXA-160
Deciders: 项目所有者（明确要求“帮我按上述优化”，承接竞品源码对照方案）
Supersedes: none
Superseded by: none

## Context

Application Context 创建的 WebView 限制 JS 对话框与 Autofill；应用级控制器直接持有 Activity View 会延长旧 Activity 生命周期。现有宿主在 Activity 销毁时释放，但全局销毁缺少身份绑定。竞品源码对照见 Context 分析。系统 JNI/Binder 缺陷与应用规避分别记录。

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

恢复窗口所需 Context 并明确资源所有权，保留惰性分配。Activity 真正销毁后的页面需显式导航，不能承诺 DOM/JS 堆恢复。无 Activity 请求以已有 ToolResult 失败路径结束，不伪造成功。跨模块 owner 接口是 Android 层契约，Core 无 Android 依赖。

## Verification

已有证据：HXA-158 应用规避、HXA-159 回归及固定竞品源码对照。所有者授权的是上述决定，不是实现已验收。

HXA-160 已完成实现验收，见 [完成记录](../completion-records/HXA-160.md)：精确命令、API29/36 实际 Context/对话框/AutofillService 填写保存、旧新 owner 交错销毁、取消/后台/重建和 JNI/Binder 有界证据分别记录。系统根因未关闭；真机与长稳仍后置。

## Reconsider when

真实 Autofill/窗口功能不兼容，旧 owner 无法释放，生产路径资源累积显著劣化，或产品明确要求无 Activity 的独立浏览器运行时。

## References

- [Context 与竞品源码对照](../development/browser-context-options.md)
- [规避报告](../development/native-reference-mitigation.md)
