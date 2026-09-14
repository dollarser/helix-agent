# 2026-09-10 第三轮审查复核与修复

所属任务：HXA-189。复核对象为 [原审查快照](improvement-review-2026-09-10.md)。以当前源码、配置和实际执行为准，不沿用旧审查的测试通过数。本轮不操作 Claude 在途长稳模拟器，也不推送代码。

## 已修复或收敛的结论

| 原项 | 复核与处置 |
| --- | --- |
| A-H1 | **PFD 泄漏属实**。PRoot `readFromStart` 的 AutoCloseInputStream 原先未关闭，成功、超限、I/O 异常均可能遗留读端；已用 use 确定性关闭。写端改用 AutoCloseOutputStream，统一 PFD 所有者关闭语义。新增五个设备回归用例，编译通过不等于设备执行通过。 |
| P-H2 | **权限入口缺失属实**。双 flavor 的权限页均可主动请求通知/日历写入权限、进入 Notification Listener 设置并查看当前状态；返回前台刷新。developer 的原 All-files 页面保留在文件权限子入口。Android 授权不创建 Agent scope/审批规则。日历只申请已有 WRITE_CALENDAR 能力，不新增读权限。拒绝后始终保留应用设置入口。 |
| P-H1 | **空态无操作入口属实**。无可用聊天 Provider 时，会话列表提供“配置模型服务”按钮，进入现有设置与 Provider 配置/连接测试。完整模板、示例 Workspace 和首个任务向导仍是独立产品增量，本轮不声称已实现全套 onboarding。 |
| P-H3、审批卡英文标签 | 删除 Advanced 的 M2 过时说法，描述“显示额外控制项，但不会自动申请/安装/执行/批准”。审批卡两处标签使用三语资源。 |
| consumer 扩展页分隔线 | 分隔线随真实内容区块出现，避免 null Skill 区留下孤立线。 |
| D-H1、D-M2 | status 删除重复历史大段，Completed 转到自动生成索引；文档门禁校验索引与真实记录，不靠范围补造完成。旧记录的测试边界保留正文。最新真机结果与历史全量结果分开。 |
| D-H2 | HXA-185 roadmap 改为有界交付完成并链接记录；24h API36 失败和 API29 在途仍单独保留，不能读成 EV-02 全绿。 |
| D-H3 | ADR-0024 标为已替代；ADR-0008 的当时 proposed 明确为历史语义并指向当前决定。新增显式当前状态词检查，接入 verify-adr；历史快照不强行改写。该规则检查 ADR Markdown 链接前的 `accepted/proposed/rejected/superseded`，不宣称自然语言全覆盖。 |
| D-M1、D-M5 | roadmap 补 M11A 总览/退出条件；移除 status 中未定义 M14 的历史分类，保留 HXA-148～151 的实际记录。明确编号不连续不代表缺实现，不为空号补造任务。 |
| D-M3 | 文档门禁新增 gitignored 链接检查；矩阵和 HXA-185 中四处本机 build 制品改成明确的本机路径文本，避免“当前机器有文件、fresh clone 断链”的假绿。 |
| D-M3b | 复核前 HXA-188 已将 Root 修复记录改链到真实 roadmap 任务；无需制造不存在的 HXA-094 完成记录。 |
| D-M4、D-L3 | 文档中心补当前交接与历史专项导航，含原审查列出的三个孤儿；M10 旧执行日志加历史快照定位，保留稳定引用，不批量搬迁旧证据。 |
| D-L1 | AGENTS 将 M7 历史 Spike 启动门禁改为已完成契约引用；ADR-0008 当前读取状态改 accepted，不提前启用未完成 Git 产品能力。 |
| D-L2 | 新增 scripts/README：常设门禁、设备/发行脚本、按日期临时脚本分组。`__pycache__/` 已被全局规则忽略，原审查“缺规则”不成立。历史验收入口有完成记录引用，保留原路径以便复现。 |
| E-M1 | 新增 check-all，CI 与本地共用 source/build/artifacts 三阶段。补 i18n 与 CLI Runtime 边界；网络制品下载、需要特定 runtime assets 的 assetGate 明确单列，不用无资产运行冒充验收。 |
| E-L2 | 锁文件清单与 Gradle dependencies 任务从 settings 的真实 include 派生，移除 35 魔法数及重复项目表；缺锁/动态语法/重复项失败。模块新增不升级版本；PRoot IPC 设备测试只复用仓库既有锁定测试依赖。 |
| E-H2（构建覆盖） | CI 增加主 App 双 flavor 及两个 companion 的 Release 构建；保留原有所有库/双 flavor Debug+Release lint。Release 构建不替代签名发行验收。 |
| E-H1（过时声明） | THIRD_PARTY_NOTICES 删除 M0 未打包的过时描述，明确当前清单仅部分归属说明，QuickJS/Zipline、Maven 传递依赖及可选 Runtime 制品仍需发行级清单。Compose BOM 是依赖约束，不是 APK 内一个组件二进制。 |

## 不作为本轮缺陷修复的建议

| 项目 | 判断与后续边界 |
| --- | --- |
| A-H1 共享 transport | 可考虑，但当前泄漏只需修复明确所有权。PRoot/CLI 的 schema、握手和恢复状态不同；重复代码本身不足以证明需要新增共享模块。先有相同失败契约再提取，避免在漏关修复中扩大跨 APK 依赖。审查中的“QuickJS 同 UID”错误：其服务使用 isolated UID，不能弱化隔离说明。 |
| A-M1 ChatService | 保持单一状态所有者；现有 facade 较长不等于职责错误。没有新的重复状态/生命周期缺陷证据，不继续机械切分。 |
| A-M2/E-M4 Spike、A-M3 eval、A-M4 convention plugins | 属构建/代码组织调整；现有 Spike 有可执行证据，eval 依赖 developer 夹具。搬迁需同步构建与设备契约，目前无运行缺陷证明，保留而非删除验收资产。 |
| P-H4、审批卡折叠、备份/迁移、会话搜索 | 产品增量单列。涉及主题对比度/系统栏、审批完整披露、Secret 迁移及搜索性能，不能用本轮小补丁代表完整交付。 |
| E-H1 完整 SBOM/notice | **发行前尚待完成**：按实际 Release 解析制品/原生库/RootFS/CLI 资产生成完整清单，保留上游许可正文/NOTICE/必要 source offer，并与 APK 对账。仅从版本表列名字无法满足义务；现有部分清单不能声称已过。 |
| E-H2 R8 | 保持现值，不将混淆当作隔离或授权防线。另开发布优化验证反射、Room、序列化、Binder、Zipline/JNI、双 companion、升级安装；当前没有 minified release 的设备证据，不能仅开开关。 |
| E-M2 重试 | 不设置“失败重试后绿即成功”的全局规则，避免隐藏确定性缺陷。长稳现有 runner 已保留失败/阶段证据；通用 flake 分类与隔离重跑可在独占设备调度时进一步建设。 |
| E-M3 漏洞扫描 | 依赖完整性不等于无漏洞，但提及曾有 CVE 的库名不能证明锁定版本受影响。本轮未在线核实 CVE，也未接入新扫描服务；发行需选择支持实际 Maven/Gradle 与原生/资产清单的锁版扫描器，声明覆盖面和误报处理。 |
| E-L1 历史 Secret 扫描、E-L3 设备 CI | 增量工程能力，无本轮已证实 Secret 泄漏；不新增扫描依赖、不借用别人的模拟器。设备 CI 需要独占资源与账号/设备生命周期约定。 |

## 验证与交接

本轮主机命令与最终结果见 [HXA-189 收口记录](../completion-records/HXA-189.md)；执行日志保存于忽略目录 `build/debug/2026-09-10/review-*.log`。不把日志链接当可克隆证据，不提交真实用户数据。

设备交接给 Claude：必须自建独占模拟器，用完关闭；不得中断在途 EV-02 或替换其冻结 Browser APK。建议 API29 和 API36、新的 App 数据沙箱：

1. `:runtime:proot-ipc:connectedDebugAndroidTest`：`com.helix.runtime.proot.ipc.PfdManifestChannelDeviceTest`，预期五项执行；验证正常/空输入/超限/无效端点关闭。若新进程被 OEM/模拟器冻结，先保留原始失败，不能将超时改 PASS。
2. App consumer/developer：`SystemPermissionsNavigationDeviceTest` 两项，确认权限页入口和空 Provider 导航；运行在无真实账号的测试沙箱。
3. 手动/自动系统流程：API33+ 首次通知允许、拒绝与永久拒绝后的应用设置恢复；Calendar 拒绝/允许；Listener 打开、返回后状态更新；developer 文件权限子页返回。API29 不应尝试 POST_NOTIFICATIONS；这些流程尚未执行，不计通过。
4. 复跑 `ConversationTopBarDeviceTest`、`SystemBarInsetsDeviceTest` 与既有 All-files UI 用例，确保权限页嵌套不破坏顶部/返回/滚动。只操作本轮自建实例。
