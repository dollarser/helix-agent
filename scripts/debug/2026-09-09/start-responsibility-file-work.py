from pathlib import Path
p=Path('docs/development/status.md');s=p.read_text().replace('无。HXA-178 已完成：','HXA-179 进行中：按职责拆分 ChatService；随后 HXA-180 完善独立文件管理，HXA-181 清理过时规范。所有者明确要求保留当前工作树，不合并 main。\n\nHXA-178 已完成：',1);p.write_text(s)
p=Path('docs/development/roadmap.md');s=p.read_text()+'''

### HXA-179 ChatService 职责拆分

状态：in progress。所有者授权保持行为的会话服务重构；允许 app/chat、相关测试、docs、scripts/debug。不合并 main。提取工具执行/结算/时间线与中断结果恢复，保留单会话准入、取消、审批和结果顺序。验收：双 app JVM、构建与测试 APK、Spotless/Detekt/lint、独占 API29/36 的工具/审批/Goal/后台/压缩回归。

### HXA-180 独立文件管理变更能力

状态：planned。所有者授权独立文件管理器的新建、重命名、复制、移动和删除；共享存储及 SAF 按真实权限和 provider 能力提供操作，Agent scope 不自动扩大。允许 app/files/UI、feature/files、core/workspace 必要复用、相关测试/docs/scripts。补充 ADR-0036 的只读首期边界。验收：冲突/越界/撤销/失败源文件保留、双版本主机与独占 API29/36 用户操作、Agent 隔离回归。

### HXA-181 当前规范清理

状态：planned。清理 status/架构/操作指南中的过时当前描述，保留 ADR 和完成记录的历史事实及取代关系；核对 HXA-179/180 的当前边界。不新增功能，不合并 main。验收：docs/ADR/i18n/secrets/diff 检查与当前代码引用核查。
''';p.write_text(s)
