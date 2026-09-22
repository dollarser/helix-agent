#!/usr/bin/env python3
"""Add the session-input queue panel resources to the three app locales."""

from __future__ import annotations

import argparse
from pathlib import Path
from xml.sax.saxutils import escape


STRINGS = {
    "session_input_delivery_title": ("Input delivery", "输入交付"),
    "session_input_delivery_count": ("Input delivery · pending %1$d", "输入交付 · 待处理 %1$d"),
    "session_input_loading": ("Loading queued input…", "正在加载排队输入…"),
    "session_input_load_failed": ("Could not load queued input. Your edits are retained.", "无法加载排队输入，已有编辑已保留。"),
    "session_input_refresh": ("Refresh", "刷新"),
    "session_input_needs_attention": ("Needs attention. Review before resuming.", "需要处理，请检查后再继续。"),
    "session_input_resume_not_accepted": ("Input was not resumed. The saved text is retained.", "输入未继续，已保留保存的文本。"),
    "session_input_revision_stale": ("This item changed elsewhere. Refresh and review it again.", "此项已在其他位置更新，请刷新后重新检查。"),
    "session_input_action_failed": ("Action failed. The saved input is retained.", "操作失败，已保留保存的输入。"),
    "session_input_row_summary": ("%1$s · %2$s", "%1$s · %2$s"),
    "session_input_steer_current": ("Supplement to the current response", "当前回复的补充输入"),
    "session_input_appended_history": ("Added to history; not included in a request yet", "已加入历史，尚未纳入请求"),
    "session_input_appended_request": ("Included in a request (local record only)", "已纳入请求（仅本地记录）"),
    "session_input_delivery_queue": ("Queue", "排队"),
    "session_input_delivery_steer": ("Steer current turn", "转向当前回合"),
    "session_input_steer_expired": ("The target turn is no longer active. Choose Queue or select Steer again from the active turn.", "目标回合已不再活动。请选择排队，或从当前活动回合重新选择转向。"),
    "session_input_state_pending": ("Pending", "待处理"),
    "session_input_state_needs_attention": ("Needs attention", "需要处理"),
    "session_input_state_appended": ("Appended", "已加入"),
    "session_input_state_withdrawn": ("Withdrawn", "已撤回"),
    "session_input_show": ("Show", "展开"),
    "session_input_hide": ("Hide", "收起"),
    "session_input_edit": ("Edit", "编辑"),
    "session_input_save": ("Save", "保存"),
    "session_input_withdraw": ("Withdraw", "撤回"),
    "session_input_resume": ("Resume", "继续"),
    "session_input_rejected_generic": ("Queued input could not be resumed. The saved input is retained.", "排队输入无法继续，已保留保存的输入。"),
    "session_input_rejected_not_found": ("Queued input is no longer available.", "排队输入已不存在。"),
    "session_input_rejected_revalidation": ("Queued input needs review because its session or configuration changed.", "会话或配置已变化，请重新检查排队输入。"),
    "session_input_rejected_changed": ("Queued input changed elsewhere. Refresh and review it again.", "排队输入已在其他位置更新，请刷新后重新检查。"),
    "session_input_rejected_queue_full": ("The session input queue is full. Try again after it drains.", "会话输入队列已满，请在队列减少后重试。"),
    "session_input_rejected_queue_bytes": ("The session input queue has reached its size limit.", "会话输入队列已达到大小上限。"),
    "session_input_rejected_target_stale": ("The selected turn is no longer active. Choose Queue or select the active turn again.", "所选回合已不再活动，请选择排队或重新选择当前回合。"),
    "session_input_rejected_configuration": ("The saved model configuration changed. Review and submit the input again.", "保存的模型配置已变化，请重新检查并提交输入。"),
    "session_input_rejected_attachment": ("An attachment changed or is unavailable. Reattach it and try again.", "附件已变化或不可用，请重新添加后重试。"),
    "session_input_rejected_delivery": ("Queued input could not be delivered. The saved input is retained.", "排队输入无法交付，已保留保存的输入。"),
}
LEGACY_KEYS = {"session_input_queue_title", "session_input_queue_count", "session_input_steer_target"}


def add_keys(path: Path, locale_index: int) -> None:
    text = path.read_text(encoding="utf-8")
    text = "\n".join(
        line for line in text.splitlines() if not any(f'name="{key}"' in line for key in LEGACY_KEYS)
    ) + "\n"
    missing = [
        f'    <string name="{key}">{escape(value[locale_index])}</string>\n'
        for key, value in STRINGS.items()
        if f'name="{key}"' not in text
    ]
    if missing:
        path.write_text(text.replace("</resources>", "".join(missing) + "</resources>"), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("project_root", type=Path)
    root = parser.parse_args().project_root
    resources = root / "app" / "src" / "main" / "res"
    add_keys(resources / "values-en" / "strings.xml", 0)
    add_keys(resources / "values-zh-rCN" / "strings.xml", 1)
    add_keys(resources / "values" / "strings.xml", 1)


if __name__ == "__main__":
    main()
