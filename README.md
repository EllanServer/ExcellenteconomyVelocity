# ExcellentEconomyVelocity

A plugin that enables cross-server payments for ExcellentEconomy under a Velocity proxy, with support for MySQL, Redis, Pub/Sub, async command suggestions, and offline payouts.

## Features

- Cross-server `/pay`: MySQL transactions, two-account row locks, balance and receive-toggle checks, and instant Redis notifications.
- Per-currency player trading switches and permissions, plus currency command prefixes such as `/money pay` and `/ellan_gold pay`.
- `/payall`: grant a currency to every player currently online across all Redis-connected Velocity nodes.
- `/payments`: Read or modify ExcellentEconomy's native `settings` JSON.
- `/eesync`: Update NightCore sync flags for a single player or the entire table.
- `/payoffline`: Distribute money to all existing players in the database, with preview confirmation code, batch processing, balance caps, audit records, and offline notifications.
- Online-safe mode: during global payouts, online players are deferred until they disconnect and backend save is completed, avoiding overwrite of in-memory balances.
- Multi-proxy online state, Pub/Sub notifications, Redis reconnect; payments can be configured to fail closed on Redis outage.
- Async command suggestions for player names, currencies, status, and common amounts.
- Hot reload through `/eev reload` or `/eevreload`; invalid replacement configuration leaves the old services running.
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
| `/eev pay <player> <amount> [currency]`, `/pay`, `/money pay ...` | `excellenteconomyvelocity.command.currency.send` |
| `/eev payments [currency] [state]`, `/payments`, `/money payments ...` | `excellenteconomyvelocity.command.currency.payments` |
| `/eev balance [player] [currency]`, `/money balance ...` | `...command.currency.balance`; other players also need `...balance.others` |
| `/money give <player> <amount>`, `/eev give ...` | `excellenteconomyvelocity.command.currency.add` |
| `/money giveall <amount>`, `/eev giveall ...` | `excellenteconomyvelocity.command.currency.addall` |
| `/money set <player> <amount>`, `/eev set ...` | `excellenteconomyvelocity.command.currency.set` |
| `/money take <player> <amount>`, `/eev take ...` | `excellenteconomyvelocity.command.currency.take` |
| `/eev sync <player\|all>`, `/eesync` | `excellenteconomyvelocity.command.sync` |
| `/eev payall <amount> [currency]`, `/payall`, `/money payall ...` | `excellenteconomyvelocity.command.currency.payall` |
| `/eev payoffline <amount> [currency]`, `/payoffline`, `/money payoffline ...` | `excellenteconomyvelocity.command.currency.payoffline` |
| `/eev status` | `excellenteconomyvelocity.command.status` |
| `/eev reload`, `/eevreload` | `excellenteconomyvelocity.command.reload` |

When `commands.register-currency-commands` is enabled, every currency id and alias is a Velocity command prefix. It overrides matching backend commands, so `/money give`, `/money set`, `/money take`, `/money giveall`, `/money pay`, `/money payments`, and `/money balance` all use the shared cross-server database path. Permissions intentionally follow ExcellentEconomy's `command.currency.*` categories.

For a player source, every operation also checks the selected currency's own permission when `permission-required` is enabled. Player-to-player transfer additionally requires `player-trading: true`:

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

`player-trading: false` blocks player-to-player payment even when a player has the permission. Admin `/payall` and `/payoffline` remain available for that currency.

LuckPerms examples (run from the Velocity console with LuckPerms Velocity installed):

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

`payoffline` only shows target count and a one-time confirmation code on first run; it executes only when run again with `--confirm <code>`. Default is `SAFE_DEFER_ONLINE`: offline records update immediately, and online records update after the player disconnects and backend save is completed.

## 安装

Requires Java 21 or newer, Velocity 3.4/3.5, ExcellentEconomy 2.8, NightCore 2.16, and the MySQL/MariaDB database used by ExcellentEconomy. Redis 6+ is recommended for multi-proxy presence and instant notifications.

1. 构建：`./gradlew clean test shadowJar`（Windows 使用 `gradlew.bat`）。
2. 把 `build/libs/ExcellentEconomyVelocity-1.0.2.jar` 放入 Velocity 的 `plugins`。
3. 首次启动后编辑 `plugins/excellenteconomyvelocity/config.yml`。
4. `users-table` 默认是 ExcellentEconomy 默认表 `excellenteconomy_users`；如果改过 `Table_Prefix`，这里也要对应修改。
5. `currencies.<id>.column` 必须与对应货币文件的 `Column_Name` 完全一致。

插件只会额外创建 `eev_transactions`、`eev_notifications`、`eev_campaigns` 和 `eev_pending_grants`，不会修改 ExcellentEconomy 代码或 JAR。

## 验证

单元测试覆盖金额解析、货币规格化与 settings JSON；设置 `EEV_TEST_DB_URL` 和 `EEV_TEST_REDIS_URI` 后还会运行真实 MariaDB/Redis 集成测试，覆盖原子支付、收款开关、同步标记、跨节点事件、在线状态及 `payoffline` 恰好一次入账。
