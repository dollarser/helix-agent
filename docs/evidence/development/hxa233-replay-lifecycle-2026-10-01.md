# HXA-233 回放生命周期：实现与主机验证

日期：2026-10-01。起点 `05e91003` / v0.0.4，加本任务未提交工作树。所有者授权依次完成剩余任务并修复发现的问题；本记录仅证明 HXA-233 的相应范围，不是整个授权任务完成。

## 生产实现

- Antigravity 请求封装新增可信宿主会话归属；实际传输与写前日志使用同一 payload/hash。短期探测明确为 ephemeral，模型不提供归属。
- 私有 Runtime 保留签名 parts，向宿主只提供每页最多 32 项的 opaque 身份、内容指纹、归属和字节数。已有同调用身份不能覆盖另一账号/模型或签名内容；不恢复 LRU。
- 宿主事务内检查全部保留会话和 TOOL_CALLS 历史，包含归档、分支及被替代/压缩前行。活跃会话保护结果交付至历史发布之间的间隙；历史损坏或超限不进入删除。
- Runtime 在提交/ACK 共用锁内检查活跃和未确认结果，复核候选文件指纹后才删除。取消请求、丢失回执、清理成功与任务成功分开。单物理维护通道无队列；超时的非协作 IPC 未退出时不会无限新增工作线程。
- 永久删除会话后尝试一页清理；仅归档不清理。Developer 存储统计提供显式检查/继续分页入口，Consumer 不加载订阅 Runtime。回执丢失显示结果未知并隐藏未经确认的删除数字；重试重新读取清单。

## 本轮修复与边界

分页使用 nullable 起点，避免漏掉空 ID 的极端历史行；元数据数值类型、总量溢出、重复/倒退游标、部分 Binder 回执、符号链接和超大文件都有拒绝或保留路径。顺带将上下文说明中的字面百分号资源标记为非格式化文本，保留中英文原内容。

旧记录没有 owner 时，只要还有会话就保守保留。异常文件、未知命名/临时文件不被当成可删除证据。每页检查有行数、字节和目录扫描上限，不宣称全量自动垃圾回收或无限历史性能。宿主引用检查与 Runtime 删除不是跨进程事务；现有历史引用、当前会话归属、在途任务和精确候选复核共同防止正常生产路径错误回收。共享应用 UID 仍不是恶意代码隔离。

## 主机实际结果

联合命令经共享 host slot：

```bash
python3 scripts/with-host-slot.py -- ./gradlew test detekt spotlessCheck \
  :app:lintConsumerDebug :app:lintDeveloperDebug \
  :app:assembleConsumerDebug :app:assembleDeveloperDebug \
  :app:assembleConsumerDebugAndroidTest :app:assembleDeveloperDebugAndroidTest \
  :runtime:cli-client:assembleDebugAndroidTest --continue --console=plain
```

最终退出码 0、BUILD SUCCESSFUL，1019 tasks：61 executed / 958 up-to-date。增量复用不称为全部强制重跑。前轮 task selector、格式及静态检查失败已修正；未确认结果测试早期因 epoch 时间被合法过期规则清理而失败，改为固定当前夹具时钟后通过，没有放宽生产 ACK 或过期规则。

| 当前 XML 范围 | tests（包含 skipped） | failures/errors | skipped |
| --- | ---: | ---: | ---: |
| CLI client | 65 | 0/0 | 0 |
| CLI app | 187 | 0/0 | 0 |
| Storage | 219 | 0/0 | 0 |
| App consumer | 1037 | 0/0 | 4 |
| App developer | 1099 | 0/0 | 4 |

新增独立单测：ReplayCalls 2、ReplayMaintenance wire/codec 4、Runtime ReplayMaintenance 7、Quiescence 2、宿主 Retention 9，共 **24/24，零跳过**。双渠道共享用例不重复统计独立场景；既有 4 个外部条件跳过不算通过。

汇总脚本 `scripts/debug/2026-10-01/summarize-hxa233-host.py` 只读取显式当前 Gradle 报告，输出 `build/hxa233-host-summary.json`，包括四个 APK 的 SHA-256。Developer APK `b3c1024b630b30aa549045ee0f198094590db94f214a7534e7b72ceb5236f2cf`，Consumer APK `337c2741080c950f611f83d1482975ddcabbac1f761a5a0fa54a408c8f5da936`；这些是未提交候选，不是 clean 正式 P5 或签名发行包。

## 设备与外部条件

**设备：not requested；真实模型/账号：未调用。** 新增设备入口：SubscriptionReplayRetentionDeviceTest 2、CliReplayMaintenanceWireDeviceTest 4、ProviderEvidenceCleanupDeviceTest 2，共 8 项独立场景，仅编译和打包。需要指定设备后执行：实际 Room 分支/归档/删除后重开、真实 Parcel/Binder 异常、界面分页/失败/重复点击。没有用主机 mock 声称真机/OEM/进程故障矩阵通过。

## 源码与制品门禁

`bash scripts/check-all.sh --artifacts`：退出码 0。双渠道 APK 组件、进程/UID、payload、launcher 及订阅 Consumer 排除检查通过。

首轮 source 门禁因 HXA-233 缺少三个标准标题而失败，已按仓库任务格式修订。复验 `bash scripts/check-all.sh --source` 退出码 0：673 Markdown、216 HXA、35 ADR、1922 个三语言资源键一致，脚本测试及秘密扫描通过；`git diff --check` 通过。本结果在下一阶段 OAuth 源码修改前取得，不自动覆盖后续阶段。
