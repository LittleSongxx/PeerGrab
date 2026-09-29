# PeerGrab 评测资产目录与保留规则

这份目录区分**可执行计划、公开报告、私有原始证据和可再生临时文件**。当前 HTTP 发压入口为 [JMeter 场景计划](jmeter/README.md)；S5 Worker 到期测量仍用[专用探针](S5_TIMELINE.md)。[运行手册](README.md)只放现行步骤，[历史运行手册](LEGACY_RUNBOOK.md)保存旧 Vegeta／Java／Python 轮次的复现方法；[早期 YAML 方案](profiles/README.md)并非 JMeter 执行配置。

| 层次 | 位置与用途 | 保留规则 |
| --- | --- | --- |
| 当前可执行计划 | `bench/jmeter/`、`bench/scripts/preflight.py`、`seed_bench.py`、`verify_fund.sql`、`cleanup.sh` | 跟踪在 Git；修改后跑测试、JMX 校验和隔离栈冒烟 |
| 当前 ECS 报告 | [JMeter ECS 复测](reports/peergrab-current/report-ecs-jmeter-20260928.md)、[同镜像原工具六场景报告](reports/peergrab-current/report-ecs-spectrum-20260928.md) | 跟踪在 Git；按镜像、工具、路径、窗口和错误分母分开引用 |
| 历史实验 | [PeerGrab 按版本报告索引](reports/peergrab-current/README.md)、[迁入前原版](reports/original-project/README.md) | 保留原结果和失效轮；不得把历史数字改标为现行 JMeter 或生产容量 |
| 私有原始证据 | Git 忽略的 `bench/runs/`；归档路径、大小和 SHA-256 见[私有归档索引](reports/private-evidence-index.json) | 当前本地共有 19 个压缩归档；不提交 JTL、JWT CSV、脱敏时序、生产备份或路由令牌 |
| 可再生临时文件 | `bench/runs/jmeter-s2-*` 逐轮展开文件、`spectrum-20260928/server-extracted/`、本地 `jmeter-local-*` 测试输出 | 只有在逐文件确认已收入对应归档且哈希匹配后才可清理；需要时从归档重新展开 |

2026-09-28 的两组核心证据包已逐项核验并保留：JMeter 客户端包 SHA-256 为 `c8bf3dc53f8e14cf7f9ca31759a41bc0e08f76aa7ec3892679b1ea5e10b941df`、服务端包为 `d4661d29a05dcdd2776c96b6fb35e354a11f7d72a8ff45a623fcb018902c6d92`；原工具六场景客户端包为 `674ed6f56ee8f904b5d88af26e789a6255cd3be8247f99295d5eb662f17c2d98`、服务端包为 `643733d076388732a9ecdcb618eff69449de6e6f4ff9c996330f26602dd1b9db`。JMeter 客户端包已加存 ECS 私有目录，远端与本地哈希一致。此前的其他私有归档继续按[索引](reports/private-evidence-index.json)保留。

本轮清理前逐文件核对 JMeter 客户端归档 **155** 个成员、原工具服务端归档 **355** 个成员与本机展开副本一致；随后仅删除这两组展开副本及可丢弃本地冒烟目录，`bench/runs/` 从约 **170 MiB** 降到 **61 MiB**。JMeter 第三轮的静态 HTML 报告另复制到私有 `bench/runs/jmeter-ecs-20260928/dashboard-round-3/` 便于查看。ECS 上已清理隔离库销毁后失效的 **26 份 JWT CSV** 和过期临时路由安装日志；证据归档与生产备份均未删除。清理记录在 Git 忽略的 `bench/runs/asset-cleanup-20260928.json` 与 ECS 私有维护目录中。

ECS 维护目录中的生产 MySQL 备份和 Compose 私有环境文件**不属于压测临时文件**。两次维护窗口的备份均经过独立恢复与校验，继续留在服务器私有目录；不得因为压测项目容器、卷已清理而删除这些备份。公开报告只记录恢复验收和归档哈希。

JMeter 可生成静态 HTML 压测报告，但它只反映那次 JTL；生产 API／Worker 目前只有指标暴露，尚无持续采集、Grafana 看板与链路追踪。
