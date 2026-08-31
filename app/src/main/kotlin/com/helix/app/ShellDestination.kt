package com.helix.app

enum class ShellDestination(
    val route: String,
    val title: String,
    val emptyState: String,
) {
    Sessions(
        route = "sessions",
        title = "会话",
        emptyState = "会话状态与 Agent 循环将在 M1–M2 实现。",
    ),
    Files(
        route = "files",
        title = "文件",
        emptyState = "Workspace、SAF 与文件管理器将在 M4 实现。",
    ),
    Browser(
        route = "browser",
        title = "浏览器",
        emptyState = "受控 WebView 浏览器与 Browser Tools 将在 M6 实现。",
    ),
    Extensions(
        route = "extensions",
        title = "扩展",
        emptyState = "MCP Client 与 Agent Skills 将在 M7 实现。",
    ),
    Permissions(
        route = "permissions",
        title = "权限",
        emptyState = "系统能力、审批与高级权限入口将在对应里程碑实现。",
    ),
    Settings(
        route = "settings",
        title = "设置",
        emptyState = "模型 Provider 和本地配置将在 M2 实现。",
    ),
    Audit(
        route = "audit",
        title = "审计",
        emptyState = "工具执行审计记录将在 M3 实现。",
    ),
}
