"""Refresh current WIP ownership and evidence; retain dated historical results."""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
docs = root / "docs/development"
notice = (
    "> 2026-09-16 接手更新：所有者已授权本任务接手并提交本工作树剩余WIP，"
    "不再等待原并行所有方。完整本地主机/构建/lint/制品门禁已通过；"
    "原27 detekt、12 lint、2项JGit阻断均为历史结果。"
    "HXA-200/201已完成；其他任务按各自剩余验收判断。"
    "依赖允许为兼容性与维护升级，须同步版本锁、验证材料和设备证据。"
    "当前证据与提交范围见[WIP接手记录](wip-takeover-2026-09-16.md)，"
    "下文旧基线/未提交/失败数字只保留追溯用途。\n"
)
for name in ["harness-2.0-next-work.md", "harness-implementation-handoff.md",
             "integrated-developer-runtimes.md", "product-completion-and-approval-plan.md"]:
    path = docs / name
    content = path.read_text()
    title, rest = content.split("\n", 1)
    path.write_text(title + "\n\n" + notice + rest)

replacements = {
    "integrated-developer-runtimes.md": [
        ("状态：实现与本地专项验证完成；未提交、推送、合并，未通过分支全量门禁或发行验收。",
         "状态：实现与本地专项验证完成，现已通过分支完整本地门禁并由接手任务收口提交；未推送、合并或通过发行验收。"),
        ("1. HXA-192：先处理当前 27 detekt、12 App lint，补原计划的 Room/Plan 集成与主分支合并门禁。",
         "1. HXA-192：本地主机门禁已清零，继续补原计划尚缺的Plan用户闭环与授权隔离设备证据；主分支合并仍需独立授权。"),
        ("不让小模型通过生成新 lock 绕过缺包。",
         "可以按所有者授权升级RootFS依赖，但必须完成来源、许可证、hash和设备验证后更新lock，不能只重算hash掩盖缺包。"),
    ],
    "harness-2.0-next-work.md": [
        ("禁止无关职责搬迁、依赖升级与整仓 suppression。",
         "禁止无关职责搬迁与整仓 suppression；依赖可按2026-09-16授权升级并补齐兼容性验证。"),
    ],
    "harness-implementation-handoff.md": [
        ("不全局 suppress、不跳过测试、不为通过门禁升级依赖。有人正在修改同一范围时不要接管或覆盖。",
         "不全局suppress或跳过本地必过测试；依赖可升级，须验证兼容性。当前剩余WIP已由所有者授权接手；之后新增并行改动仍须核对归属。"),
    ],
    "terminal-and-background-execution-plan.md": [
        ("不迁移订阅凭据，也不升级 RootFS/依赖来顺带解决其他问题。",
         "不迁移订阅凭据。RootFS/依赖可按所有者授权升级以解决兼容性或维护问题，须同步lock、来源与对应验证。"),
        ("不 reset/stash 他人改动，不升级依赖。",
         "不reset/stash他人改动；依赖允许升级，必须更新lock并验证Android兼容性。"),
    ],
    "product-completion-and-approval-plan.md": [
        ("状态：已授权计划，未实现本包新增功能。",
         "状态：已授权计划；HXA-200/201已验收，其余条目按status推进。"),
        ("| 全量门禁 | 最新交接记录 fail-fast 于两项 JGit TrustAll lint；旧27 detekt不是当前失败数 | 按当前报告续查；本次未重跑全量，不伪造其他后续 gate 已通过 |",
         "| 全量门禁 | 2026-09-16完整本地门禁通过，原JGit/detekt问题已修复 | 见WIP接手与基线修复记录；真实账号、远端CI与发行仍独立验收 |"),
    ],
}
for name, changes in replacements.items():
    path = docs / name
    content = path.read_text()
    for old, new in changes:
        assert old in content, (name, old)
        content = content.replace(old, new, 1)
    path.write_text(content)
