# 扩展市场与签名索引分支交接说明 (Branch Handover)

更新日期：2026-09-22  
当前分支：`codex/marketplace-catalog`  
分支基线：`main` (包含已交付的 HXA-126 OAuth 核心层)  
当前状态：**已完成全部开发、测试、多语言与全量 CI 门禁（1287/1287 PASS），随时可合并入 main**

---

## 1. 本分支已完成的工作 (DONE)

### A. HXA-212 / ADR-CONNECTORS-005：内置精选扩展市场
1. **精选目录深度扩充**：
   - 由 6 项扩充至 9 项典型精选扩展（Cloudflare Docs、Web Research、GitHub Operations、Linear Workspace、GitLab 项目协同、Code Review、SQL 数据库助手、系统与设备诊断助手、工作区整理助手）。
   - 覆盖免鉴权公共服务、Token 认证、API Key 认证及本地只读执行四类安全边界。
2. **交互体验与发现能力**：
   - 支持全量关键字实时搜索（名称、简介、作者、标签匹配）；
   - 支持分类筛选（All / Connectors / MCP / Skills）及标签（Tag）一键联动过滤；
   - 完整错误与成功反馈、安装防抖保护。
3. **卡片内原地生命周期管理**：
   - 支持展开卡片直接执行「启用」/「停用」（联动端点与 Skill 禁用状态）；
   - 支持卡片内直接触发「卸载」，并弹出二次确认对话框（明确提示清除关联凭据与端点）；
   - 提供「重新安装」按钮，允许已安装项目原地重新安装或覆盖。
4. **无限制降级安装与干净替换**：
   - 市场安装逻辑（`MarketplaceService.install`）解除对降级安装的限制；
   - 检测已安装同名目标，若内容哈希发生变更（升级、降级或版本替换），自动先彻底卸载清理旧版本，再写入新版本，杜绝悬空残留文件与重复 ID 冲突；同版本安装保持幂等。
5. **国际化资源严谨对齐**：
   - `values/strings.xml`、`values-en/strings.xml`、`values-zh-rCN/strings.xml` 1447 个资源词条 100% 保持一致，Kotlin 源码 0 硬编码 CJK。

### B. HXA-130 / ADR-CONNECTORS-004：Connector 签名索引与离线验签
1. **严格数据契约** (`SignedConnectorIndex.kt`)：
   - `ConnectorIndexEntry`：固定 SemVer 版本（禁止 latest）、内容寻址（`archiveSha256` 64 位十六进制）、HTTPS 来源、文件大小上限 16 MiB、声明许可证规范；
   - `ConnectorIndex`：包含 `schemaVersion`、`publisherId`、`keyId`、单调 `sequence`、`issuedAt`/`expiresAt` 时间戳窗口及包列表（上限 1000）。
2. **严格 JSON 递归下降解析器** (`SignedConnectorIndexParser.kt`)：
   - 纯标准库 RFC 8259 解析，拒绝重复键、浮点/指数、未转义控制符、前导零；
   - 限制嵌套深度 <= 32，限制大小 <= 1 MiB，拒绝未知字段与不支持的 schema 版本。
3. **密码签名验证与无限制降级** (`SignedConnectorIndexVerifier.kt`)：
   - 信任根校验：`keyId` 必须存在于调用方传入的受信任公钥表（`trustedKeys`）中，索引不得自带新 Key；
   - **默认不限制降级安装**：`verify()` 默认 `allowDowngrade = true`。当 `sequence <= lastKnownSequence` 时，不报 `SEQUENCE_REGRESSION` 致命错误，而是继续验签并在成功结果中返回 `isDowngrade = true` 审计标记供调用方追溯；亦保留显式传参 `allowDowngrade = false` 严格拦截回滚的能力；
   - 分离密码签名校验：使用 `SHA256withECDSA` 校验原始 JSON 字节与 DER 编码签名，任何单字节篡改均导致验签失败。
4. **离线合成测试资产** (`ConnectorIndexTestFixtures.kt`)：
   - 隔离的合成密钥对 `TEST_FIXTURE_KEY`，明确标识为测试夹具，不进入生产信任根。

### C. 文档与 ADR 状态同步
- [`004-signed-index.md`](../adr/connectors/004-signed-index.md)：状态为 `accepted`，更新无限制降级安装与 `isDowngrade` 审计标记规范；
- [`005-curated-marketplace.md`](../adr/connectors/005-curated-marketplace.md)：状态为 `accepted`，同步 9 项目录、搜索/标签过滤、降级覆盖替换规范；
- [`HXA-130.md`](../completion-records/HXA-130.md)：新建交付完成记录，记录离线验签与降级测试；
- [`HXA-212.md`](../completion-records/HXA-212.md)：新建交付完成记录，记录精选市场、降级覆盖与端到端测试。

### D. 验证与门禁证据 (All Passed)
- `./gradlew :extensions:skills:test :app:testConsumerDebugUnitTest :app:testDeveloperDebugUnitTest`：全部通过；
- `SignedConnectorIndexVerifierTest`：覆盖有效签名、改动一字节、错误 key、未知 keyId、损坏 DER 编码、过期、未来时间戳、降级安装允许/拦截、重复包/键、超长载荷；
- `MarketplaceCatalogTest` & `MarketplaceDeviceTest`：覆盖 9 组精选项目唯一性与字段检查，以及真机环境下 MCP 与 Skill 项安装、幂等重装、降级覆盖替换及卸载联动；
- `./scripts/check-i18n.sh`：1447 个资源键在 base/en/zh-rCN 之间 100% 对齐；
- `./scripts/check-all.sh --source`：通过；
- `./scripts/check-all.sh`：1287 项 Gradle 任务全部执行通过，36 份依赖锁无漂移，Lint Vital、四大变体 APK（consumer/developer debug/release）校验全部通过。

---

## 2. 容易混淆的关键点澄清 (CLARIFICATIONS)

1. **HXA-126（Connector OAuth 2.0 PKCE 核心层）**：
   - **事实**：该任务的**本地核心实现已于 2026-09-21 合并入 `main` 分支**（通过提交 `21c1f5da` / `66152626` 并已在远端 CI 通过）。
   - **混淆原因**：路线图中保留“进行中”，是因为根据仓库严格交付准则，两家真实第三方外部服务的线上账号连通和动态注册作为条件未完成范围独立记账，但**本地代码与协议层早已合入主线，不需要在当前分支重复开发**。
2. **降级安装（Downgrade）的处理策略**：
   - **签名索引层（Verifier）**：默认 `allowDowngrade = true`，当 sequence 小于等于已知 sequence 时判定为有效降级，返回 `Success(isDowngrade = true)`，不抛出异常或拒绝；
   - **安装运行时（MarketplaceService）**：安装新版本或旧版本（只要哈希发生变动），先完全卸载清理旧版本，再进行新版本写入，保证存储干净、单实例生效；
   - **UI 操作层**：卡片展开后提供明确的“重新安装”按钮，支持原地重新安装或覆盖。
3. **网络与安全边界**：
   - 当前内置市场纯本地静态内嵌（`MarketplaceCatalog`），浏览、搜索和详情展开不发起任何远程网络请求；
   - 新安装的受保护 Connector 默认处于未激活状态，端点不自动连接网络，不硬编码任何真实 Secret，必须由用户手动在管理页启用并配置凭据。

---

## 3. 本分支提交历史与合并指引

分支提交链（共 4 次具名提交）：
1. `b01483c0`：`docs(marketplace): add ADR-CONNECTORS-005 and HXA-212 completion record`
2. `86ab6524`：`feat(marketplace): deepen curated market and implement ADR-CONNECTORS-004 / HXA-130 signed index verification`
3. `b235392e`：`feat(connectors): allow downgrade installation and clean version replacement`
4. `3b3d609b`：`docs(marketplace): synchronize ADR-CONNECTORS-005 and HXA-212 with downgrade support`

**合并指引**：
当所有者决定将扩展市场与签名索引合入主线时，只需：
```bash
git checkout main
git merge --ff-only codex/marketplace-catalog  # 或常规 merge
```
因 `codex/marketplace-catalog` 严格基于最新 `main` 延伸且门禁全通，可无冲突平滑合入。

---

## 4. 剩余及后续工作清单 (PENDING / NEXT WORK)

| 阶段 | 任务编号 | 工作内容 | 状态与依赖 |
| :--- | :--- | :--- | :--- |
| **近期紧邻** | **分支合并** | 将 `codex/marketplace-catalog` 合并入 `main` | 代码与门禁已就绪，等待所有者确认指令 |
| **扩展下一项** | **HXA-129** | **Connector 更新差异预览 (Diff Preview)** | 当检测到已安装项目有新版本时，自动对比清单、端点及 Skill，生成结构化差异供用户审查确认后覆盖 |
| **硬件专项** | **HXA-199** | **终端专项物理设备验收** | 双终端架构已交付，等待接入真实物理 OEM 设备进行 HOME、锁屏、Doze、16 KiB 内存页与长稳测试 |
| **外部账号** | **HXA-125 / 190** | **真实服务/订阅联调** | 依赖外部真实可用账号进行最终网络连通闭环 |
| **最终发行** | **HXA-120 ~ 123** | **发布准备与商店合规** | 渠道审计、签名密钥、发布候选包验收与提交 |
