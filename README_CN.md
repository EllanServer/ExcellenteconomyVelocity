# ExcellentEconomyVelocity

一个让 ExcellentEconomy 在 Velocity 代理下跨服支付的插件，支持 MySQL、Redis、Pub/Sub、异步命令补全和离线发钱。

## 功能

- 跨服 `/pay`：MySQL 事务、双账户行锁、余额与收款开关校验、Redis 即时通知。
- 每种货币可单独开关玩家交易、分配独立权限，并支持 `/money pay`、`/ellan_gold pay` 这样的货币前缀命令。
- `/payall`：向所有 Redis 互联的 Velocity 节点上当前在线玩家发钱。
- `/payments`：读取或修改 ExcellentEconomy 原生 `settings` JSON。
- `/eesync`：更新 NightCore 的同步标记，支持玩家或全表。
- `/payoffline`：向数据库内所有已有玩家发钱，带预览确认码、分批处理、余额封顶、审计记录和离线通知。
- 在线安全模式：全服发放时在线玩家延后到退出且后端保存完成后入账，避免覆盖其内存余额。
- 多代理在线状态、Pub/Sub 通知、Redis 断线重连；可配置支付在 Redis 故障时 fail closed。
- 玩家名、货币、状态和常用金额的异步命令补全。
- `/eev reload`、`/eevreload` 热重载；新配置无效时旧配置与服务会继续运行。
- `/eev status`、`/eev balance`、交易流水和批次进度表。

## 问题（无法实现）

本项目完全兼容 ExcellentEconomy （当前验证兼容2.8.0 未来版本或旧版本可能不兼容）MySQL 写入会立即完成，Redis 能让多个 Velocity 节点立刻获知在线状态与通知；但原版 ExcellentEconomy **不订阅 Redis**，所以后端内存余额只能由 NightCore 自己轮询数据库刷新。若玩家在后端产生了尚未保存的余额变更，后续保存仍可能覆盖代理刚写入的值。纯 Velocity 插件无法消除这个竞态。
（期待作者修改/更新插件，我这边可能会pull request希望未来版本能加上）

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
| `/eev pay <玩家> <金额> [货币]`、`/pay`、`/money pay ...` | `excellenteconomyvelocity.command.currency.send` |
| `/eev payments [货币] [状态]`、`/payments`、`/money payments ...` | `excellenteconomyvelocity.command.currency.payments` |
| `/eev balance [玩家] [货币]`、`/money balance ...` | `...command.currency.balance`；查他人还需 `...balance.others` |
| `/money give <玩家> <金额>`、`/eev give ...` | `excellenteconomyvelocity.command.currency.add` |
| `/money giveall <金额>`、`/eev giveall ...` | `excellenteconomyvelocity.command.currency.addall` |
| `/money set <玩家> <金额>`、`/eev set ...` | `excellenteconomyvelocity.command.currency.set` |
| `/money take <玩家> <金额>`、`/eev take ...` | `excellenteconomyvelocity.command.currency.take` |
| `/eev sync <玩家\|all>`、`/eesync` | `excellenteconomyvelocity.command.sync` |
| `/eev payall <金额> [货币]`、`/payall`、`/money payall ...` | `excellenteconomyvelocity.command.currency.payall` |
| `/eev payoffline <金额> [货币]`、`/payoffline`、`/money payoffline ...` | `excellenteconomyvelocity.command.currency.payoffline` |
| `/eev status` | `excellenteconomyvelocity.command.status` |
| `/eev reload`、`/eevreload` | `excellenteconomyvelocity.command.reload` |

启用 `commands.register-currency-commands` 后，每个货币 id 和 alias 都会成为 Velocity 命令前缀并覆盖后端同名命令。因此 `/money give`、`set`、`take`、`giveall`、`pay`、`payments`、`balance` 都走共享数据库的跨服处理。权限分类刻意参考原版 ExcellentEconomy 的 `command.currency.*` 层级。

命令来源是玩家时，若 `permission-required` 开启，每种操作还会检查该货币自己的权限；玩家互转另外要求 `player-trading: true`：

```yaml
currencies:
  money:
    column: "money"
    player-trading: true
    permission-required: true
    permission: "excellenteconomyvelocity.currency.money"
    aliases: ["ellan_gold"]
  admin_coins:
    column: "coins"
    player-trading: false
    permission-required: true
    permission: "excellenteconomyvelocity.currency.admin_coins"
```

`player-trading: false` 时，即使玩家有权限也不能互相转账；管理员仍能用 `/payall` 和 `/payoffline` 发放该货币。

安装 LuckPerms Velocity 后，在 Velocity 控制台执行示例：

```text
lpv group default permission set excellenteconomyvelocity.command.currency.send true
lpv group default permission set excellenteconomyvelocity.command.currency.payments true
lpv group default permission set excellenteconomyvelocity.command.currency.balance true
lpv group default permission set excellenteconomyvelocity.currency.money true
lpv group admin permission set excellenteconomyvelocity.command.currency.add true
lpv group admin permission set excellenteconomyvelocity.command.currency.addall true
lpv group admin permission set excellenteconomyvelocity.command.currency.set true
lpv group admin permission set excellenteconomyvelocity.command.currency.take true
lpv group admin permission set excellenteconomyvelocity.command.currency.payall true
lpv group admin permission set excellenteconomyvelocity.command.currency.payoffline true
lpv group admin permission set excellenteconomyvelocity.command.reload true
lpv group admin permission set excellenteconomyvelocity.currency.money true
```

`payoffline` 第一次执行只显示人数和一次性确认码；再次附加 `--confirm <确认码>` 才会真正执行。默认 `SAFE_DEFER_ONLINE`：离线记录立即更新，在线记录在玩家退出并等待后端保存后更新。
