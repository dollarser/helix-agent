# HXA-273：Skill 调用资格与本次用户激活

日期：2026-10-11。Owner明确授权完成272～275、模拟器/现有Qwen和阶段本地提交；本记录只覆盖273。

## 实现

SkillLoader严格读取`user-invocable`和`disable-model-invocation`布尔值，缺省用户和模型均可调用。管理预览独立；模型目录、正文和resource入口检查同一资格。`context`、`agent`、`hooks`等未实现执行字段保留用于检查，但不展平执行。`allowed-tools`不导入授权。

会话Skill面板新增“使用一次”和可选参数；它修改输入草稿，不直接发送、不修改全局资格。输入框旁显示当前技能并可移除。缓存与Activity恢复保存准确source/name/hash及参数；旧发送回执不会清除用户随后修改的激活。正常接受路径把有界激活元数据写入现有SessionInput，准确输入指纹和队列容量包含参数，没有新状态库。

实际读取依据宿主接受的Room事实查询原session/turn；工具参数不提供`userInvoked`通道。普通Turn仅使用本次输入，连续Goal沿原Goal绑定使用；独立任务和fork不继承。取消持久撤销，保留原接受元数据作审计。持久激活查询在内容锁之外完成，队列Room准入事务不重复进入内容锁；并发回归证明持久查询期间其他线程仍能读取内容目录。排队和Steer交付重验当前来源、启停、资格及准确版本；失效输入停在需处理状态，不自动替换为同名最新内容。旧消息编辑/显式重试路径不能静默丢弃附带激活。

参考[Claude Code Skill调用资格](https://code.claude.com/docs/en/skills)和[Codex Skill指引](https://learn.chatgpt.com/docs/build-skills)，采用明确调用方和内容发现/读取分离；Helix将本次调用绑定到既有持久输入与Goal，而不是新增命令解释器或临时激活服务。未复制参考实现。

开发期Room仍是单一v1 baseline；本次增加SessionInput字段并更新core导出schema，不增加历史迁移。未覆盖安装日常APK或操作其数据库，后续覆盖升级需遵守既有备份/重建提示。

## 验证

全仓`spotlessCheck detekt test`、双渠道Debug lint/APK/AndroidTest APK及storage测试APK通过。最终主机日志`build/hxa273-lock-host.log`退出0；此前补齐存储测试的`build/hxa273-host-final.log`退出0。最终source检查`build/hxa273-lock-source.log`退出0，206主机runner fixture、文档/ADR/三语言/secret通过；其中负向解析fixture的FAIL_FUNCTIONAL文本不是设备执行。

Developer自有API36 arm64实例：会话技能选择、参数绑定、纯Skill资源、原生零工具内容、真实Qwen3.8-27B仅用户Skill读取，共5/5通过、0skip，冻结最终候选证据`build/hxa273-final-device/`。调用前模型目录与实际读取入口拒绝该用户Skill；宿主实际接受一次输入后，Qwen通过正常`skills.read`成功。新独立turn仍不可发现该Skill。测试仅使用合成内容，没有账号发送、手机动作或用户文件写入。

Storage独立测试APK在自有API36上17/17通过、0skip，`build/hxa273-storage-device-recheck/`包含Goal连续身份、跨会话隔离、接受/取消竞态、Room重开与持久撤销。所有设备实例由owned runner启动、记录hash并关闭，不借用日常模拟器。

Standard同一自有API36、640dpi（270dp宽）、1.5倍字体的选择与参数输入2/2通过、0skip，`build/hxa273-final-consumer-narrow/`记录实际字体、制品和退出事实；控件保持可达。中文参数使用Compose输入断言，未将其当作拼音IME验收。

失败尝试保留：首次Developer5项有2个fixture前置错误——旧选择测试误选未在会话中选择的原生内容，新增模型fixture在连接测试前创建会话。修正为独立内置Skill及先probe再创建，原隔离/启停断言保留，随后完整5项通过。一次storage启动因刚关闭实例的端口尚不可绑定而未执行测试，改用另一空闲端口后完整17项通过。窄屏启动首次CLI密度值未获runner支持，未启动设备；runner增加有界640dpi和1.5字体选项后完整2项通过。早期编译/静态失败逐项修正，未放宽全仓门禁或删除行为断言。

真实模型结果只覆盖当前Qwen和合成只读任务，不推导其他模型、OEM、长稳或真实账号成功；渐进装载收益仍由278独立验收。
