package com.helix.app.profile

/**
 * Compile-time availability of the ADVANCED entry (ADR-0005: ADVANCED only
 * appears in the developer variant, and must be switched on explicitly after
 * the user reads the risk explanation; ADR-0006: the developer variant IS the
 * single direct-distribution main app, shown to users simply as "Helix").
 *
 * [ADVANCED_RISK_SUMMARY] is the in-app risk explanation shown before the
 * explicit switch; it states the ADR guarantees verbatim in product terms:
 * the switch itself grants nothing — no system permission is requested, no
 * Runtime is installed, no Root session is opened, no new network endpoint is
 * reached; every capability is enabled individually, scoped and revocable, by
 * its own later milestone.
 */
internal object AdvancedProfileAvailability {
    const val ADVANCED_AVAILABLE: Boolean = true

    const val ADVANCED_RISK_SUMMARY: String =
        "切换为 Advanced 本身不授予任何能力：不会自动申请系统权限、不会安装 Runtime、" +
            "不会请求 Root、不会连接新的网络端点，也不会扩大可用工具。\n\n" +
            "Advanced 开放的是受控的高级能力（All-files、Accessibility、离线 PRoot、" +
            "Root、精确局域网 origin 等）。每项能力仍需单独启用、限定 scope、可立即撤销；" +
            "当前版本尚无高级能力被启用。\n\n" +
            "两个配置共用的安全内核不变：模型不能自我授权；schema 验证、Policy、逐次审批、" +
            "审计与撤销规则在 Advanced 下不被关闭或降级。"
}
