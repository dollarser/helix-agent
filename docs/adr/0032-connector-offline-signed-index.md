# ADR-0032: Connector 离线签名索引与来源验证

Status: proposed
Date: 2026-09-08
HXA: HXA-130
Deciders: pending
Supersedes: none
Superseded by: none

## Context

HXA-130 首批仅允许设计及离线索引 fixture，且依赖 HXA-129 的版本所有权和更新恢复。当前安装器校验内容 hash，但同源提供文件及 hash 不能独立证明发布者身份；真实市场、自动下载和在线更新均未实现。

## Decision

提议先定义可离线验证的索引格式与固定测试 corpus，不实现市场网络运行时。本记录未批准；不添加任何生产信任根或发布签名私钥。

- 索引为版本化 UTF-8 JSON，附带对精确原始字节的分离签名；JSON reader 拒绝重复键、未知版本、超出大小/条目/字符串界限。索引上限 1MiB、1000 条目；限制先由离线 fixture 验证，再按实际材料调整。
- 签名候选为 P-256/SHA-256 ECDSA，公钥采用 SPKI、签名采用 DER；选择 Android 既有可验证算法，避免为单一市场功能升级依赖。API29/36 的 provider/编码边界须在正式实现前实测，不仅凭宿主 openssl 成功。
- 索引含 publisherId、keyId、单调 sequence、issuedAt/expiresAt，以及 packageId、固定 version、source URL、archive SHA-256、字节大小、声明许可证、notice/source 链接、兼容性要求。条目只能使用固定版本和内容地址，不接受 latest、任意镜像回退或内嵌脚本。
- 信任根独立于下载索引，由发行方批准的公钥或用户明确导入的 key fingerprint 建立；索引不能自带新 key 并自动获得信任。发布密钥不进入源码、APK 或普通 CI 日志。初版测试 key 明确标为 fixture，不进入生产 allowlist。
- 原始签名验证通过后才解析和展示为已验证来源；签名失败、过期、未知 key、回退 sequence 均不自动安装。时钟不可用时标记无法确认有效期，不把缓存当最新。
- 更新只生成 HXA-129 的固定 hash 差异预览；用户确认后才安装，签名不是 enablement、Tool Approval 或许可证合规证明。
- 许可证检查区分声明、文本是否可取得、资产/依赖实际义务是否审核。缺许可证或无法绑定来源的条目保持不可发布；不因为源仓库有 MIT 文件就给所有 bundled CLI 推定 MIT。
- 离线 fixture 覆盖有效签名、改动一字节、错误 key、损坏签名/编码、过期、sequence 回退、重复 package/version、hash/size 不匹配及未知字段/版本。只用自建合成包，不拷贝第三方业务代码或凭据。

## Alternatives considered

- 只校验 SHA-256：保留用于完整性，不能替代独立来源身份。
- 直接上线 GitHub latest 下载：无法稳定绑定版本与恢复，且扩大当前 HXA-130 范围，不选择。
- 立即建设完整 TUF/透明日志/在线 key 轮换：可作为后续方向，但当前缺发行运维条件，先不引入新的完整供应链系统。

## Consequences

形成可审查的来源/版本数据契约与 fixture；不产生可用的在线市场。密钥发放、轮换、撤销、离线过期体验与发布者身份是后续真实发布成本，须独立验收。

## Verification

当前只有设计提议，尚未创建生产 verifier、发行 key 或市场。required before acceptance：所有者审查索引和来源契约；HXA-129 完成后才启动 HXA-130 离线 fixture 实现。专项命令在启动前固定，至少包含确定性 corpus 校验、双 API 签名验证、文件/记录边界以及 docs/ADR/secrets 门禁。网络下载、市场 UI、自动更新及密钥发布不在本次接受范围。

## Reconsider when

需要在线撤销/轮换、多个发布者委派、真实镜像、自动更新或发行组织正式身份时，先独立记录运维与信任模型，不把 fixture 公钥升级成发行信任根。

## References

- [ADR-0031：版本所有权与恢复（proposed）](0031-connector-version-ownership-journal.md)
- [ADR-0029：现有精确内容安装](0029-skill-and-mcp-authoring-installation.md)
- [Connector 架构](../architecture/connector-portability.md)
- [HXA-130 roadmap](../development/roadmap.md)
