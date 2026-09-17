# 2026-09-17 调试脚本归档

此目录保留当日缺陷定位及设备验收的复现入口，不是产品能力或测试通过证明；结果与适用源码见对应完成/修复记录。原始日志、数据库快照、设备内容和生成制品只放忽略的 `build/`，不入库。

- `hxa194/`、`hxa202/`、`hxa203/`：当日命令详情、任务旅程及产物交付验收。`dump-*.init.gradle`、`print-*.init.gradle` 是依赖特定 Gradle 内部结构的历史探针，`format-only-*` 仅为当时的格式排查，不替代完整门禁。
- `inspect-latest-conversation.py`、`read-conversation-evidence.py`、`read-conversation-limits.py`：当日真机会话故障的只读诊断，需显式传入设备和私有输出路径；会读取业务正文，仅在用户授权诊断时运行。不是 ADR-AGENT-005 的产品 JSONL 导出实现，也不保证运行中数据库的跨文件事务快照。
- `run-closeout-context-matrix.sh`：整合后模型结果/UI分离、预算继续及压缩的四象限定向回归，使用已有独占模拟器 runner；必须重新核对本机 AVD 与端口，默认命令为 `bash scripts/debug/2026-09-17/run-closeout-context-matrix.sh build/closeout-context-new`，输出目录必须是新的。

设备脚本需仓库根工作目录、已构建的测试 APK，以及有效 `JAVA_HOME`/`ANDROID_HOME`。创建并验证自己拥有的模拟器后才可运行设备子脚本；清理脚本只能用于该运行拥有的测试安装。不得借用用户真机或他人的模拟器来复现会卸载、清理或改变权限的测试。

本轮只对历史脚本进行可移植路径及语法检查；没有重新执行历史 Gradle 探针或全部设备矩阵。HXA-209 的 seccomp PoC 另留在前一日期目录，其历史问题陈述不恢复已撤下的统一禁网要求。
