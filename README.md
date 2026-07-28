# ExcellentEconomyVelocity

A plugin that enables cross-server payments for ExcellentEconomy under a Velocity proxy, with support for MySQL, Redis, Pub/Sub, async command suggestions, and offline payouts.

## Features

- Cross-server `/pay`: MySQL transactions, two-account row locks, balance and receive-toggle checks, and instant Redis notifications.
- `/payments`: Read or modify ExcellentEconomy's native `settings` JSON.
- `/eesync`: Update NightCore sync flags for a single player or the entire table.
- `/payoffline`: Distribute money to all existing players in the database, with preview confirmation code, batch processing, balance caps, audit records, and offline notifications.
- Online-safe mode: during global payouts, online players are deferred until they disconnect and backend save is completed, avoiding overwrite of in-memory balances.
- Multi-proxy online state, Pub/Sub notifications, Redis reconnect; payments can be configured to fail closed on Redis outage.
- Async command suggestions for player names, currencies, status, and common amounts.
- `/eev status`, `/eev balance`, transaction logs, and batch progress table.

## Limitation (Cannot Be Solved in This Plugin Alone)

This project is fully compatible with ExcellentEconomy (currently verified with 2.8.0; future or older versions may be incompatible). MySQL writes complete immediately, and Redis lets multiple Velocity nodes instantly know online status and notifications. However, vanilla ExcellentEconomy **does not subscribe to Redis**, so backend in-memory balances can only be refreshed when NightCore polls the database. If a player has unsaved backend-side balance changes, a later save may still overwrite the value just written by the proxy. A pure Velocity plugin cannot eliminate this race condition.
(I hope the author updates the plugin; I may open a pull request so future versions can support this.)

Therefore, in production you must set the following in every ExcellentEconomy currency file:

```yaml
Synchronized: true
```

And enable shared MySQL in `plugins/ExcellentEconomy/engine.yml`, for example:

```yaml
Database:
  Type: MYSQL
  Sync_Interval: 1
  Table_Prefix: excellenteconomy
```

This usually reduces visible delay to about one second, but truly near-zero-latency backend in-memory updates require a messaging interface provided by ExcellentEconomy/NightCore, or an additional backend bridge. Under the constraint of "no changes to original plugins, Velocity-only plugin," this cannot be honestly guaranteed.

## Commands

| Command | Permission |
|---|---|
| `/eev pay <player> <amount> [currency]`, `/pay` | `excellenteconomyvelocity.pay` |
| `/eev payments [currency] [on\|off\|toggle\|status]`, `/payments` | `excellenteconomyvelocity.payments` |
| `/eev balance [player] [currency]` | Self: `excellenteconomyvelocity.balance`; Others: `excellenteconomyvelocity.balance.others` |
| `/eev sync <player\|all>`, `/eesync` | `excellenteconomyvelocity.admin.sync` |
| `/eev payoffline <amount> [currency]`, `/payoffline` | `excellenteconomyvelocity.admin.payoffline` |
| `/eev status` | `excellenteconomyvelocity.admin.status` |

`payoffline` only shows target count and a one-time confirmation code on first run; it executes only when run again with `--confirm <code>`. Default is `SAFE_DEFER_ONLINE`: offline records update immediately, and online records update after the player disconnects and backend save is completed.

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
