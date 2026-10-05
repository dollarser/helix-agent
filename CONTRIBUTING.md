# Contributing to Helix / 参与贡献

欢迎提交问题、可复现的缺陷修复、文档改进与测试。中文或英文均可。
Bug reports, reproducible fixes, documentation, and tests are welcome in Chinese or English.

## 开始前 / Before you start

- 阅读 [AGENTS.md](AGENTS.md)、[当前状态](docs/development/status.md)和相关设计决策；大型功能先通过 Issue 讨论范围，避免重复已完成的工作。
- Read the current scope and relevant ADR before changing behavior. Discuss substantial features in an Issue first.
- 按[开发环境说明](docs/development/environment.md)配置环境。保留无关改动，使用独立分支，提交仅包含本次工作。
- Follow the development setup, preserve unrelated changes, and keep your branch and commits focused.

## 验证 / Validation

```sh
./scripts/check-all.sh --source
# 按影响范围运行模块测试与构建 / Run affected module tests and builds.
```

完整主机门禁见 [scripts/README.md](scripts/README.md)。UI 或设备行为变更应记录实际设备/API、构建渠道、通过/失败/跳过项；编译成功不能替代设备验证。自动化 Agent 使用设备或消耗真实服务额度须取得本次任务明确授权，GitHub CI 不启动模拟器。

See the scripts guide for full host gates. Record device/API, edition, and pass/fail/skip results for device behavior changes. AI agents require explicit task authorization for device testing or paid/live services. GitHub CI is host-only.

## 提交 PR / Pull requests

说明问题、最终行为、验证结果和剩余限制；涉及契约变化时同步现有 ADR/文档。不要削弱测试来消除失败。日志、截图及生成产物放入忽略的 `build/`，不要提交密钥、账号、用户数据、签名或机器路径。

Describe the problem, resulting behavior, validation, and remaining limitations. Update existing contracts when needed. Do not weaken tests to obtain a pass, or commit credentials, user data, signing material, generated artifacts, or machine-specific paths.

## 来源与许可证 / Sources and licenses

遵循项目现有 [Apache-2.0](LICENSE) 许可证。引入第三方内容时标注来源并保留相应许可信息；新增依赖或运行时资产按 [第三方说明](THIRD_PARTY_NOTICES.md)核对，不复制授权不兼容或来源不明的代码。

Follow the existing project license. Attribute third-party content and retain its license information; review new dependencies and assets under the third-party notice policy. Do not copy code with incompatible or unknown licensing.
