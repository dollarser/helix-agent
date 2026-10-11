# HXA-274：插件就绪投影与恢复导航验收

日期：2026-10-11。范围：Owner授权完成272～275、独立模拟器与现有本机Qwen，允许阶段性本地提交。本记录只证明274有界交付，不关闭真机黑屏、其他OEM、长期运行或真实账号边界。

## 实现与采用的设计

- PluginReadinessSnapshot同源提供UI与请求装配的事实：组件来源、安装revision、配置是否保存、当前配置是否已加载、会话选择和本请求资格。MCP比较实际bridge的endpoint/账号引用/启用配置；旧连接不证明新配置生效。Mobile Use复用宿主配置与后端只读事实，Browser只检查现有host，不创建WebView或注册新插件。
- 一次投影统一读取Skill清单及当前调用资格，避免按组件重复加载。内容可用与模型本请求可读分开；用户专用内容未由可信输入激活时显示REQUEST_UNAVAILABLE。模型目录不授予工具权限，执行仍由Dispatcher重新准入。
- UI删除原因字符串判断，缺项先解释再由用户进入现有恢复入口；取消不选择、不保存。部分Skill可用不因MCP未连接而整体禁用。版本、后端、endpoint提供方与账号引用收进默认折叠详情，变更提示下一请求生效。
- TurnRecoveryActions返回携带会话/Turn/原因/目标类型的数据，重新检查Room事实和准入；ChatScreen只处理本次点击返回的当前目标。删除服务持有页面导航回调，不新增事件总线。页面旋转、重订阅不重放，切换会话后旧目标拒绝。

参考[Claude Code插件故障指引](https://code.claude.com/docs/en/plugins/troubleshooting)：组件配置失败应明确说明原因并保留其余组件；参考[Android UI events](https://developer.android.com/topic/architecture/ui-layer/events)：页面拥有导航，业务检查提供可消费数据。Helix保留现有Room/权限owner，不引入桌面端自动服务启动或整包授权。

## 主机结果

完整命令：

```sh
./gradlew spotlessCheck detekt test :app:lintConsumerDebug :app:lintDeveloperDebug :app:assembleConsumerDebug :app:assembleDeveloperDebug :app:assembleDeveloperDebugAndroidTest :app:assembleConsumerDebugAndroidTest -PincludeSpikes=true
./scripts/check-all.sh --source
```

最终全仓主机日志为`build/hxa274-host-delivery.log`，退出0；source最终日志为`build/hxa274-source-delivery.log`，退出0，包含206项host runner fixture。负向fixture打印FAIL_FUNCTIONAL是被测试的解析输入，不是当前设备失败。

新增/保留断言包括：部分可用、保存不等于加载、未选择、实际请求资格、MCP目标与账号变化、旧恢复目标身份失效、取消、原非自动重放和权限边界。没有放宽全仓门禁。

## 独立API36与真实模型

自有只读AVD `Helix_HXA271_API36`，API36、arm64；均由`run-owned-emulator.py`冻结制品、独占启动并关闭。未借用日常AVD、未更新日常APK、未操作真机、未消费真实第三方账号。

| 当前实际执行 | 结果 | 原始证据 |
| --- | --- | --- |
| Developer设置说明3、会话选择5、恢复8、内容3与Qwen1 | 20/20，0跳过；140.786秒 | `build/hxa274-device-final/` |
| 最后调用资格/共享投影补验：内容3与真实Qwen1 | 4/4，0跳过 | `build/hxa274-request-device/` |
| Standard最终窄屏/大字体：设置说明3、选择5、详情3 | 11/11，0跳过；density640、fontScale1.5 | `build/hxa274-consumer-final/` |

组合恢复覆盖本轮实际Activity旋转、读取恢复数据不跳转、会话切换拒绝旧目标、网络/权限修复不重放、显式重试只创建一次新请求。未执行额外实际进程死亡两阶段协议，不能把普通持久事实测试当成本轮强杀验收。Qwen为明确配置的本机`localhost:30008`、`Qwen3.8-27B`；只操作合成Skill，真实skills.read完成并保留模型轨迹。窄屏输入为Compose合成数据，未重验中文IME拼音组合。

最后请求补验APK hash见下；最终窄屏制品与进程身份保留在各目录的artifacts.json、owner.json、closed.json。

## 失败尝试与边界

初轮`build/hxa274-device/`20项中1项失败：夹具误把包声明endpoint当作已保存连接配置。修正后保留安装时未保存断言，再通过生产registerDisabled明确配置并验证未连接；修正轮通过。格式/静态规则、已删除源文件的文档链接及Compose成员方法错误import的失败日志保留在`build/hxa274-*`，最终修复而非跳过。早一轮Standard 10/10保留于`build/hxa274-consumer-narrow/`，不重复累计为额外能力覆盖。

Browser这里只读host状态；统一原生贡献由276承接，资源释放由277承接。274不是Root/Shizuku/无障碍操作成功或真机黑屏闭合的证明。完整OEM、长期生命周期及真实认证仍需独立有界验收。

请求补验APP SHA-256：`34c1a22ea372e954b229ae459dd89a8921532bddbfc7c4aaf5eaacaddf8628b4`；测试APK：`2c96cc52a45f1a0be9bef159b27da0c3368fa340d8a91c52797f42e0d7af1d3b`。
