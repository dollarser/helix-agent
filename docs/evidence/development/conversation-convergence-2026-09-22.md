# 214/215 对话交互联合收敛

日期：2026-09-22。本轮完成214普通输入区与215会话内修订的联合验证，再合入main并验证对应远端CI。所有者随后已授权216设计与核心开发在独立 `codex/hxa-216-input-delivery` 分支推进；其实现状态以该分支及后续独立验收为准，不计入本记录。129安装替换和217来源记录尚未实施。

## 整合范围

- `codex/hxa-214-core`、`codex/hxa-214-ui`：持久草稿、发送回执、稳定Turn ID停止及普通输入区接线。
- `codex/hxa-215-revision`：最新用户消息原会话修订，保留Room v25、替代历史与JSONL映射。
- `codex/marketplace-ui-tests`：保留独立UI覆盖，增强真实类型过滤、搜索和中英文大字体断言。
- 217设计按轻量来源记录收窄，仍为proposed；不重复保存正文，不提前建设详情页或独立配额系统。

已有独立分支的设备数字继续保留在各自记录中，不作为联合源码通过证明。期间另一任务切换共享checkout并保存了stash，本轮已完整恢复到独立worktree；没有合入该任务的路演文档或代码。

## 实际修复

1. 普通输入区立即为文本/附件修改生成新意图身份；写入按CAS串行，只有匹配的Accepted回执可以清稿。发送时保留不可变快照，默认自动保存则在取得互斥锁后读取最新编辑内容，避免发送成功后重新写回旧稿。
2. 旋转时服务可能继续提交或保存。恢复后的输入区按真实Turn回执补查，保存可识别同一意图已由旧实例落盘。恢复查询等待服务提交锁，附件同步等待输入区锁，避免在确认清理或回执清库后的挂起窗口修改旧请求身份；真正的用户编辑仍立即生成新身份。回执清库和本地状态结算使用不可取消的短本地临界段；初始化先结算已接收回执，再恢复当前仍保留的稿件附件，既不重新挂载已消费附件，也不删除已编辑的新稿附件。
3. 修订回执仅由修订编辑器确认；普通输入区不能先清除修订草稿。恢复和编辑入口保留修订目标，空普通草稿不阻挡修订，非空草稿仍受保护。保存后关闭修订对话框不会立即重开，可返回列表或从同一消息重新打开。
4. 导航先等待草稿保存；保存失败保留内容和重试入口。附件恢复按会话绑定并校验可用性；图片恢复复用经hash、类型和尺寸验证的标准化快照，不重复注册固定路径，也不覆盖已绑定文件。
5. 持久CANCELLING与后续阶段写入在同一Room事务内协调。停止先于模型流、工具步骤或压缩提交时按取消结算；已完成工具结果仍按顺序记录，但不再创建下一次模型调用。
6. 市场类型筛选改为自动换行，避免窄屏英文大字体下末项溢出而无法点击。

仍沿用现有Goal新输入行为；本轮不启用运行中发送队列或Steer，也不新增Agent工具、常驻服务或执行框架。

## 验证记录

验证日志、复制APK、源码摘要、测试清单、模拟器owner与清理凭据位于忽略目录 `build/conversation-convergence/`。重型命令使用 `scripts/debug/2026-09-18/with-host-slot.py`，JDK17、workers=2、parallel=false，每次设备运行使用新建独占模拟器。

- `host-all-final.log`：`./scripts/check-all.sh --all` exit0；全模块JVM、格式/detekt、多变体Lint、debug/release构建、36份锁和两flavor的debug/release APK边界全部通过。
- `receipt-restore-final.log`：4项回执竞态UI测试通过（已接收附件恢复、确认中旋转、附件修订回执归属、保存后关闭重开）。
- `build-accepted-restore.log`、`build-restore-final.log`：格式、detekt、consumer应用JVM及双flavor应用/测试APK构建通过；草稿状态单测17项通过。
- `storage-devices.log`：API29/36存储迁移与JSONL共88项通过；对应存储源码未被后续UI修复修改。
- `final-consumer-r5`、`final-developer-r4`：最终源码下API29/36 × consumer/developer各147项回归全部通过；consumer恢复采用修复driver后exit0的 `final-consumer-recovery-r6`，developer恢复来自整体exit0的 `final-developer-r4`。8个普通进程恢复旅程各自seed/verify通过。`final-summary.json`重新读取全部最终报告并断言回归588、恢复JUnit 16、存储88，即 **692 PASS、0 FAIL、0 SKIP**。

最终应用/测试APK SHA-256分别为 consumer `e767da1e709a8a6cda886ad5de6da3213e36ea521543b75bc88b0d3db5d4dc72` / `c605c54f20cfb7dee3e1ee92edb7113e6af5370fb64ea83d8cad25750c837591`，developer `4381f9c9639969edd88e1fb4f4c708717bcdacfee29388526268a60106b725e1` / `005bf203b8670da35c78e9fab6d1f7e59f84e675893014c1f9861f0fed9fb032`。storage独立制品为 `2f6c12bdd8559028a1302e454068dbad66fe909364137c6df086d32d0c83f1f0` / `f4c5742ef6c454c61e56ed03756ecedff3f86de7d6aac5760db0a47eb2a34037`；后续UI修复未修改storage源码，因此不伪装为同一APK。

早期设备回归中的失败完整保留。修正了空文本样式断言、停止意图与终态的区别、每条消息token开销、页面滚动及软键盘关闭时序。最终developer API36一度147项中146项通过；失败快照证明两个Turn均COMPLETED、草稿已清、消息已替代，只有修订editor未关闭。根因是 `acceptedRevision` ack触发 `screen.messages` 更新后取消effect，跳过后续 `onClose`；短的不可取消本地结算修复后定向1/1及完整developer矩阵通过。该生产修复也改变consumer APK，因此旧consumer通过未拼接计数，重建后以 `final-consumer-r5` 重跑回归。

`final-consumer-r5` 最后一个API36修订恢复seed 1/1后，SIGKILL已使旧PID消失，但ActivityManager首次启动仍报告把intent交给已杀task，屏幕实际停在Launcher，driver找不到会话而exit1；这不是应用断言通过。driver改用 `am start -W` 并在该API36窗口重试一次，仍要求新PID不同，再以 `final-consumer-recovery-r6` 完整重跑consumer四个恢复旅程。没有移除测试、固定方法总数或将零执行/跳过算作通过。

## 进程恢复的证明边界

214旅程使用普通MainActivity编辑并保存草稿、SIGKILL主PID、以新PID恢复；已接收输入仅查询回执，不重发。取消旅程预置持久CANCELLING以及RUNNING/PENDING调用，验证启动恢复后为INTERRUPTED、未知结果仍未知、没有新调用或执行，重复恢复幂等。它不冒充“点击Stop瞬间杀进程”；真实点击Stop的结算由独立UI测试覆盖。

215旅程继续验证普通应用中的修订编辑/保存/杀PID/恢复。模拟器host的进程管理权限不等于应用Root能力或物理OEM验收。196/199物理设备、125/126真实服务账号、订阅实付和发行签名/渠道缺口仍独立保留。

| 最终旅程 | 主PID变化 | 结果 |
| --- | --- | --- |
| consumer API29 214 | 2672 → 3257 | 通过 |
| consumer API29 215 | 2818 → 3537 | 通过 |
| consumer API36 214 | 3328 → 4568 | 通过 |
| consumer API36 215 | 3268 → 5199 | 通过 |
| developer API29 214 | 2896 → 3496 | 通过 |
| developer API29 215 | 3058 → 3853 | 通过 |
| developer API36 214 | 3697 → 4597 | 通过 |
| developer API36 215 | 3392 → 4521 | 通过 |

## 旧工作树证据保全

清理前已将7个旧worktree的根build归档至主工作区忽略目录 `build/worktree-evidence/<worktree-name>/build/`，共3391个普通文件、18,030,370,878字节，源/目标相对路径、大小与SHA-256逐一一致。`manifest.jsonl`和`summary.json`保存完整映射；`Helix-connector-install-design`无根build，明确跳过。仅保全证据尚不代表已删除分支。BioHelix独立任务、历史stash和v0.0.1不在清理范围。
