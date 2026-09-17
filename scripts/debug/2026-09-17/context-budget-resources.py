"""Add the context/budget UX's three-language resource contract."""
from pathlib import Path
from xml.sax.saxutils import escape

entries = {
    'budget_total_help': ('Total usage adds the input and output of every model call, including repeated history and summaries. It is not the context window or an exact price.', '累计用量会叠加每次模型调用的输入和输出，包括重复发送的历史和摘要；它不是上下文窗口，也不等于实际费用。'),
    'budget_custom_active': ('Custom request limits are active. Review Advanced limits if work stops early.', '当前使用自定义请求上限；任务过早停止时，请检查高级限制。'),
    'budget_advanced': ('Advanced request limits', '高级请求限制'),
    'budget_auto_input': ('Automatic input capacity', '自动管理输入容量'),
    'budget_input_help': ('Automatic uses the model window, output reserve and platform ceiling. A custom input cap includes instructions, tool definitions, history and images; reducing it can stop even short conversations.', '自动模式结合模型窗口、输出余量与平台上限。自定义输入上限包含指令、工具定义、历史和图片；设得过小会让短对话也无法继续。'),
    'budget_calls_help': ('One tool round can contain multiple calls. Model calls also include summaries; whichever limit is reached first stops the turn.', '一个工具轮可包含多个调用。模型调用次数还包括摘要；先达到的上限会停止本轮。'),
    'budget_recommended': ('Restore recommended budgets', '恢复推荐预算'),
    'budget_saved': ('Saved. Applies to new turns; running turns and existing Goals keep their current limits.', '已保存。对新轮次生效；运行中的轮次和已有 Goal 保留原有上限。'),
    'budget_input_limit': ('The single-request input cap was reached. Increase it or use automatic input capacity in Settings. Saved tool results are retained.', '达到单次输入上限。请在设置中提高上限或启用自动输入容量；已完成的工具结果仍保留。'),
    'budget_output_limit': ('The model reached its output limit. Increase the output allowance or continue from the saved results.', '模型达到输出上限。可提高输出额度，或从已保存结果继续。'),
    'budget_total_limit': ('This turn used its total token allowance. Saved results are retained; explicitly continue to start a new bounded turn.', '本轮累计 token 额度已用尽。结果已保留；明确继续可启动新的有界轮次。'),
    'budget_message_limit': ('The retained message count exceeds the request limit. Compact history before continuing.', '保留的消息数量超过请求上限，请先压缩历史再继续。'),
    'budget_continue': ('Continue from saved results', '从已有结果继续'),
    'budget_continue_prompt': ('Continue the unfinished task using the existing conversation and settled tool results. Check completed work before deciding what remains; do not repeat completed actions merely because the previous turn stopped. Tool execution success alone is not proof that the requested result is correct.', '请依据当前对话和已结算的工具结果继续未完成的任务。先核对已完成的工作，再决定剩余步骤；不要仅因上一轮停止就重复已完成的操作。工具执行成功本身不代表任务结果已验证正确。'),
    'budget_request_detail': ('Task context input estimate before compaction (tokens): %1$d / %2$d. Instructions: %3$d; tool definitions: %4$d; history/results: %5$d; images: %6$d. Total used at that point: %7$d / %8$d. Input after usage calibration: %9$d; context window: %10$d.', '压缩前任务上下文估算：输入 %1$d / %2$d token。指令 %3$d，工具定义 %4$d，历史与结果 %5$d，图片 %6$d。当时累计已用 %7$d / %8$d token。用量校准后准入输入 %9$d，上下文窗口 %10$d。'),
}
root = Path(__file__).resolve().parents[3]
for directory, lang in [('values', 0), ('values-en', 0), ('values-zh-rCN', 1)]:
    path = root / 'app/src/main/res' / directory / 'strings.xml'
    text = path.read_text()
    import re
    for key, pair in entries.items():
        text = re.sub(rf'<string name="{key}">.*?</string>', lambda _: f'<string name="{key}">{escape(pair[lang])}</string>', text)
    added = ''.join(f'    <string name="{key}">{escape(pair[lang])}</string>\n' for key, pair in entries.items() if f'name="{key}"' not in text)
    path.write_text(text.replace('</resources>', added + '</resources>'))
