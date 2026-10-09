# Mobile Use 返回原对话：2026-10-09

范围：[HXA-250](../../development/tasks/HXA-250.md)。用户报告任务完成后仍停留第三方 App。源码确认现有 Skill 只要求验证后结束；悬浮窗返回交互仅在用户点击时调用宿主 openConversation，并会停止自动任务，不适合提示模型点击它收尾。保留该手动接管行为。

修改 Skill 首段、详细收尾流程、Skill 摘要及 ui.launch 工具描述：验证业务结果→发现原宿主包→授权内启动→确认原对话→最终回复。尊重显式停留、前台活动和手动接管；返回未知/拒绝不影响已有业务成功事实，不猜包/对话、不扩大权限、不重放或循环导航。沿用现有工具，不声称提示就是可靠的强制导航实现。

`build/mobile-use-return-host-r3.log`：现有 Mobile Use 主机测试 216/216，零失败/跳过；全仓 detekt 与双渠道 Debug APK 构建通过。`build/mobile-use-return-format-final.log` 最终 spotlessCheck；`build/mobile-use-return-source-final-r2.log` 源码/文档/ADR/i18n/密钥门禁。首次检查及格式应用复跑因两条提示字符串超过 120 字符失败，拆分字符串修正；首次文档门禁因任务标题不符合现有模板失败，修正为三个规定章节，没有删除或跳过测试。Developer APK 的 assets 内确认含 Task-completion handoff；Consumer 按现有渠道不包含该插件资产，保持渠道契约。没有新增框架、依赖或工具合同版本。

设备验证 not requested；没有操作模拟器、使用真实模型额度、更新安装或清理设备数据。APK 打包成功不代表已安装；模型遵循率、实际正确会话返回、渠道/OEM 行为尚无本次运行证据。
