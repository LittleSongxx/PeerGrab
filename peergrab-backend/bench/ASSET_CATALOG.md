# PeerGrab 评测资产目录与保留规则

当前只保留一套当前评测资产，历史轮次已清理：

| 层次 | 位置与用途 | 保留规则 |
| --- | --- | --- |
| 当前公开报告 | [2026-11-04 最终评测](reports/peergrab-current/report-20261104-final.md) + [脱敏证据摘要](reports/peergrab-current/final-2026-11-04-evidence.json) | 跟踪在 Git；数字必须带环境、工具、窗口、错误分母和正确性校验 |
| 当前可执行计划 | `bench/jmeter/`、`bench/scripts/`、`bench/S*_*.md` | 跟踪在 Git；修改后跑测试、JMX 校验和隔离栈冒烟 |
| 私有原始证据 | Git 忽略的 `bench/runs/20261104-final/`（日志、manifest、IT surefire 文本） | 不提交 JWT、口令、生产备份或路由令牌；需要时从该目录复现 |
| 可清理临时文件 | `bench/runs/` 下除 `20261104-final/` 外的内容、`target/`、`node_modules/`、`dist/` | 已清理旧归档与旧工具包；不再保留 2026-08/09/10 历史报告 |

## 当前口径

- 读容量：隔离 bench、8C ECS、HTTP、10k 热页，**2450 RPS×60s×3 轮 0 错误**；2500 RPS 起每轮 2 次超时。
- S4 结算：**200/32 218.6 TPS、P99 247.8ms；500/64 387.7 TPS、P99 254.7ms**（同机短批次，生产同款池、多跑腿）。
- 自动结算：**1s 扫描 + 500/批，P99 11.3s**（已上生产）。
- S6：Redis 暂停 25s，250 次详情读 0 错误、P95 107.5ms（100ms 超时）；Broker 暂停 30s，结算 180ms 返回、恢复后 outbox 约 3s。
- 所有数字只代表注明的隔离栈/窗口，不是全站容量或 SLA。
