# ExcellentEconomyVelocity

一个不修改后端 ExcellentEconomy、只安装在 Velocity 上的群组服经济扩展。

## 功能

- 跨服 `/pay`：MySQL 事务、双账户行锁、余额与收款开关校验、Redis 即时通知。
- `/payments`：读取或修改 ExcellentEconomy 原生 `settings` JSON。
- `/eesync`：更新 NightCore 的同步标记，支持玩家或全表。
- `/payoffline`：向数据库内所有已有玩家发钱，带预览确认码、分批处理、余额封顶、审计记录和离线通知。
- 在线安全模式：全服发放时在线玩家延后到退出且后端保存完成后入账，避免覆盖其内存余额。
- 多代理在线状态、Pub/Sub 通知、Redis 断线重连；可配置支付在 Redis 故障时 fail closed。
- 玩家名、货币、状态和常用金额的异步命令补全。
- `/eev status`、`/eev balance`、交易流水和批次进度表。

## 兼容边界

本项目不会修改 ExcellentEconomy，也不会向 Paper 后端安装桥接插件。MySQL 写入会立即完成，Redis 能让多个 Velocity 节点立刻获知在线状态与通知；但原版 ExcellentEconomy **不订阅 Redis**，所以后端内存余额只能由 NightCore 自己轮询数据库刷新。若玩家在后端产生了尚未保存的余额变更，后续保存仍可能覆盖代理刚写入的值。纯 Velocity 插件无法消除这个竞态。

因此生产环境必须在每个 ExcellentEconomy 货币文件中设置：

```yaml
Synchronized: true
```

并在 `plugins/ExcellentEconomy/engine.yml` 中启用共享 MySQL，例如：

```yaml
Database:
  Type: MYSQL
  Sync_Interval: 1
  Table_Prefix: excellenteconomy
```

这能把通常的可见延迟压到约一秒，但“真正接近零延迟地修改后端内存”必须由 ExcellentEconomy/NightCore 提供消息接口，或另装后端桥接；在“不修改原插件且只做 Velocity 插件”的限制下无法诚实保证。

## 命令

| 命令 | 权限 |
|---|---|
| `/eev pay <玩家> <金额> [货币]`、`/pay` | `excellenteconomyvelocity.pay` |
| `/eev payments [货币] [on\|off\|toggle\|status]`、`/payments` | `excellenteconomyvelocity.payments` |
| `/eev balance [玩家] [货币]` | 自己：`excellenteconomyvelocity.balance`；他人：`excellenteconomyvelocity.balance.others` |
| `/eev sync <玩家\|all>`、`/eesync` | `excellenteconomyvelocity.admin.sync` |
| `/eev payoffline <金额> [货币]`、`/payoffline` | `excellenteconomyvelocity.admin.payoffline` |
| `/eev status` | `excellenteconomyvelocity.admin.status` |

`payoffline` 第一次执行只显示人数和一次性确认码；再次附加 `--confirm <确认码>` 才会真正执行。默认 `SAFE_DEFER_ONLINE`：离线记录立即更新，在线记录在玩家退出并等待后端保存后更新。

## 安装

要求 Java 25、Velocity 4、ExcellentEconomy 2.8、NightCore 2.16，以及 ExcellentEconomy 正在使用的 MySQL/MariaDB。多代理和即时通知建议 Redis 6+。

1. 构建：`./gradlew clean test shadowJar`（Windows 使用 `gradlew.bat`）。
2. 把 `build/libs/ExcellentEconomyVelocity-1.0.0.jar` 放入 Velocity 的 `plugins`。
3. 首次启动后编辑 `plugins/excellenteconomyvelocity/config.yml`。
4. `users-table` 默认是 ExcellentEconomy 默认表 `excellenteconomy_users`；如果改过 `Table_Prefix`，这里也要对应修改。
5. `currencies.<id>.column` 必须与对应货币文件的 `Column_Name` 完全一致。

插件只会额外创建 `eev_transactions`、`eev_notifications`、`eev_campaigns` 和 `eev_pending_grants`，不会修改 ExcellentEconomy 代码或 JAR。

## 验证

单元测试覆盖金额解析、货币规格化与 settings JSON；设置 `EEV_TEST_DB_URL` 和 `EEV_TEST_REDIS_URI` 后还会运行真实 MariaDB/Redis 集成测试，覆盖原子支付、收款开关、同步标记、跨节点事件、在线状态及 `payoffline` 恰好一次入账。
