"""Register the owner-authorized Harness closeout plan; never create completion evidence.

Run from main, supplying the Harness checkout as an argument. Existing HXA-190/191 state stays.
"""
import sys
from pathlib import Path

for root in [Path.cwd(), *map(Path, sys.argv[1:])]:
    roadmap = root / "docs/development/roadmap.md"
    text = roadmap.read_text()
    assert "### HXA-192 " not in text, "HXA-192 already allocated; review instead of overwriting"
    roadmap.write_text(text.rstrip() + """

### HXA-192 Harness 2.0 迁移修复、门禁与集成收尾

2026-09-14 所有者授权：归档两份研究文档，处理需要深入判断的迁移/CLI 契约/决策边界，并规划小模型后续工作。状态：有界源码修复和主机验证已完成，完整收尾未完成；当前实现仅在 `worktree-harness-2.0`，main 仅同步文档。

允许范围：研究材料/索引/治理；Harness 的 core/storage 迁移及对应测试，runtime/cli-client、runtime/cli-app 的契约回归；后续仅限交接 R1 报告点的局部 API/UI/资源/Detekt 修复，R2 的 Plan/METADATA 暴露、Dispatcher/存储/审计集成，R3 的独占设备验证。保持 Goal、审批、UID、schema 和恢复边界；不扩大成全套 Harness 或新自动化项目。

顺序、具体路径、验收和已执行证据见 [专项交接](harness-2.0-next-work.md)：R1 原门禁 → R2 Plan 契约/集成 → R3 迁移与产物设备闭环 → R4 架构接受和最终集成记录。ADR-PERMISSIONS-002 在 Harness 分支仍 proposed，不能提前当作现行授权；本 HXA 不关闭 main 既有 HXA-190/191，不授权提交、推送或合并。
""")
    matrix = root / "docs/development/verification-matrix.md"
    text = matrix.read_text()
    lines = text.splitlines()
    index = next(i for i, line in enumerate(lines) if line.startswith("| HXA-191 |"))
    lines.insert(index + 1, "| HXA-192 | Harness 2.0 迁移、门禁与集成 | 进行中：[执行包及真实命令](harness-2.0-next-work.md)；CLI/storage 主机及迁移 SQL 通过，Android Room/Plan 集成与完整 check-all 待 R1～R4，不提前接受 ADR-PERMISSIONS-002 |")
    matrix.write_text("\n".join(lines) + "\n")
    status = root / "docs/development/status.md"
    text = status.read_text()
    note = ("HXA-192（2026-09-14）Harness 分支专项：研究材料已分类；v16 迁移与 CLI 旧契约测试已局部修复，"
            "CLI 162 项和 storage 89 项主机回归通过，androidTest 编译通过；设备未运行。"
            "Detekt 仍有 27 项，lint 待局部处理，ADR-PERMISSIONS-002 仍 proposed。源码修复位于 `worktree-harness-2.0`，"
            "main 此轮仅有文档改动；均未提交、推送或合并。小模型按[专项交接 R1～R4](harness-2.0-next-work.md)推进，"
            "不重复已完成修复，不将局部主机通过写成整体验收。\n\n")
    status.write_text(text.replace("## In progress\n\n", "## In progress\n\n" + note, 1))
