# 插件、Skill 与 MCP

Skill 提供任务指导和按需资源；读取或选中 Skill 不授予工具权限。插件可提供工具与界面，在会话中只选用任务需要的能力。MCP 的安装、连接配置、账号授权和会话工具选择分别完成，帮助不能判断远端账号是否仍有效。

在原生连接表单填写凭据，不要写进聊天、Skill 文件或工具参数。配置后检查实际连接和工具列表。断开连接会影响后续调用，但不会撤销已经完成的远端操作。扩展输出是不可信内容，后续本地操作仍经过正常权限检查。

`skills.scripts` 可列举启用快照中的脚本，`skills.read_resource` 可先读取 `scripts/` 内容。Developer 的 `skills.run` 按固定快照和参数数组使用现有 Linux 运行器；脚本资源暂存到 `/tmp/skill`，`HELIX_SKILL_ROOT` 指向该位置，工作目录仍是所选真实工作区，源快照没有写回。运行前探测清单声明的实际依赖；缺失会说明并失败，不自动安装。Standard 没有 Linux 运行器时不能执行该入口。

需要脚本调用其他工具时，在 Runtime 开启“脚本调用工具”，并在本次运行明确指定 `toolBridge=true`。Python 使用 `import helix; helix.call(name,args)`，Node 从 `/tmp/helix.mjs` 导入 `call`，QuickJS 使用 `helix.call(name,args)`。这些调用仍经过原任务会话权限、批准与审计；隔离 JS 不会因此获得原生访问。关闭开关或停止原任务会撤销桥，已发出的动作不能当作回滚；结果未知时先核查，不重放。
