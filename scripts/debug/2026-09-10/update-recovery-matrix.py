from pathlib import Path
p=Path('docs/development/verification-matrix.md');s=p.read_text();line=next(l for l in s.splitlines() if l.startswith('| HXA-181 |'));s=s.replace(line,line+'\n| HXA-182 | 双 app JVM、构建/测试 APK、Spotless/Detekt/lint、独占 API29/36 文件/聊天和发布中进程死亡后的 UI 恢复、docs/ADR/i18n/secrets | 验证中；主机与设备结果独立记录 |');p.write_text(s)
