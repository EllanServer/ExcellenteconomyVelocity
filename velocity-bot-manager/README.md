# VelocityBotManager

一个独立、可构建为单 JAR 的 Velocity 插件原型。每个机器人都是嵌入插件进程的
Minecraft 1.21.11 无界面客户端，并通过真实协议连接回 Velocity。

## 当前实现

- 多机器人配置、错峰启动、启停、即时重连、状态和命令控制。
- Offline UUID 固定生成，支持 Velocity forced-host 握手地址。
- Login、Configuration、Play 状态跟踪；协议库处理压缩、加密、Known Packs 和 KeepAlive。
- Client Information、Cookie 空响应、Teleport Confirm 与位置回执。
- 强制资源包门禁所需的 ACCEPTED、DOWNLOADED、SUCCESSFULLY_LOADED 状态序列。
- VeloAuth 风格的登录/注册提示匹配与登录优先回退流程。
- 目标服切换、后续传送命令、死亡重生和指数退避重连。

`ACCEPT_WITHOUT_DOWNLOAD` 只模拟客户端状态，不会下载或校验资源包。若 CraftEngine
还使用自定义插件消息或服务端回调校验，必须在真实测试网络中捕获断线原因并补充对应处理。

## 构建

在仓库根目录执行：

```powershell
.\gradlew.bat -p velocity-bot-manager clean check shadowJar
```

产物位于 `velocity-bot-manager/build/libs/VelocityBotManager-0.1.0-SNAPSHOT.jar`。
`check` 还会从最终阴影 JAR 中加载一次已重定位的协议客户端，避免只验证未打包的开发类路径。
只需把这个 JAR 放入 Velocity 的 `plugins` 目录。首次启动会生成
`plugins/velocitybotmanager/config.yml`。

## 首次联调

1. 保持示例机器人的 `enabled: false`，配置本地监听端口、virtual-host、账号密码和目标服。
2. 启动 Velocity，使用控制台 `/vbot start IronFarm01`。
3. 用 `/vbot status IronFarm01` 检查 LOGIN、CONFIGURATION、PLAY 或断线原因。
4. 在登录服确认注册/登录，再确认机器人进入 Leaf、执行挂机点传送并加载目标区块。
5. 单机器人验证通过后再设为 `enabled: true`，随后逐个增加机器人。

管理权限为 `velocitybotmanager.admin`。命令包括：

```text
/vbot list
/vbot status <id>
/vbot start <id>
/vbot stop <id>
/vbot reconnect <id>
/vbot command <id> <command...>
/vbot reload
```

## 尚需真实网络验证

代码构建与单元测试不能证明后端区块一定加载。完成 MVP 验收仍需要目标网络上的：

- VeloAuth 首次注册与后续登录各一次抓取；
- CraftEngine 资源包门禁通过；
- Velocity 切换至 Leaf 1.21.11；
- UUID、玩家数据和插件识别结果核对；
- 挂机区块、刷铁机/随机刻实测；
- 至少 24 小时在线与代理/后端重启恢复测试。
