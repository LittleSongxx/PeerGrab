# PeerGrab 按版本归档的评测

**当前 HTTP 发压工具是 JMeter。**[同镜像 JMeter ECS 复测](report-ecs-jmeter-20260928.md)记录了其现行计划、真实样本数、失败档和资金验收；S5 Worker 到期事件仍由专用探针测量。[工具迁移前的六类场景实测](report-ecs-spectrum-20260928.md)使用 Vegeta／Java／Python，仍保存 Broker 暂停时“结算已提交、客户端却超时”等故障反例。两份报告都只代表注明的负载与维护窗口，不是全站容量或长期 SLA。

JMeter 游标首屏以固定用户池和吞吐定时器做 500 目标 RPS × 180 秒 × 3 轮，实际 269,608 次请求全部通过，最差轮 P99 255 ms；600 目标 RPS 长档第二轮有 1 次连接超时，第三轮按门禁停止。新旧连接／定时模型不同，不能直接计算优化增益。

| 日期 | 报告 | 环境与用途 |
| --- | --- | --- |
| 2026-09-25 | [隔离栈冒烟](smoke-20260925-current.md) | 本地 Docker，短时链路验证；不是容量数字 |
| 2026-09-26 | [ECS 同机基线](report-ecs-20260926.md) | 4 vCPU ECS、当时 10 Mbps、生产并行运行，隔离栈有 CPU 配额；只代表该配置 |
| 2026-09-26—27 | [ECS 维护窗口实测](report-ecs-max-20260926.md) | 生产停机备份后，50 Mbps、隔离栈无 CPU 硬配额、异机 HTTPS 发压；含 S1–S5、完整公网路径与消融 |
| 2026-09-27 | [ECS 8 vCPU 复测](report-ecs-8cpu-20260927.md) | 升级至 8 vCPU、100 Mbps 后，复测完整公网路径 S2、持久化结算 S4 与同单抢单 S1；保留独立证据包 |
| 2026-09-27 | [本次上线后 ECS 复测](report-ecs-release-20260927.md) | 新应用镜像上线后，在停产维护窗口隔离栈复测 S1–S5；S2 使用回环 API 和异机 SSH 转发，不是完整公网路径 |
| 2026-09-28 | [优化分析与复测](performance-optimization-analysis-20260929.md) | 自动结算扫描、钱包锁路径和详情预热的阶段性单轮结果 |
| 2026-09-28 | [当前镜像 ECS 六类场景实测](report-ecs-spectrum-20260928.md) | `5ace500`，8 vCPU／100 Mbps ECS 停站后逐场景独立栈；含游标首屏完整公网 HTTPS、S1–S5 及 Redis／Broker 暂停的 S6 冒烟，附生产恢复与私有证据校验和 |
| 2026-09-28 | [JMeter ECS 复测](report-ecs-jmeter-20260928.md) | 同业务镜像，JMeter S1/S2/S3/S4 及 Redis 故障详情读；S5 Worker 计时探针和 MQ 单笔故障未重跑，保留私有 JTL／资源证据 |

各 ECS 报告的生产负载、带宽、CPU 配额、代码、请求路径或发压位置不同，不能合并成一条容量曲线。2026-09-27 旧镜像的完整 HTTPS 专项在 600 RPS 档三轮各 108,000/108,000 HTTP 200，650 RPS 长档出现 23 次传输超时；当前镜像改用真实广场游标首屏，在 600 RPS 长档的第三轮出现 4 次传输超时。两者不能直接计算优化增益，也都没有测出最大稳定容量。4 vCPU 窗口、回环 API 和 SSH 转发的结果另有各自限定条件，详见对应报告。

[4 vCPU 脱敏证据清单](ecs-max-evidence.json)记录该轮源码与镜像身份、私有证据包校验和。原始 JSONL、日志和请求汇总保存在 Git 忽略的 `peergrab-backend/bench/runs/ecs-max-evidence-20260926.tar.gz`，ECS 仓库外也有私有副本；不把包含临时路由标识与测试细节的原始包公开提交。

8 vCPU 复测的[脱敏证据清单](ecs-8cpu-evidence.json)与原始证据包另行保存；升级前后的数字仅在请求、数据、路径和统计时长一致时对照。

2026-09-27 上线后五份有效轮次的原始归档及 SHA-256 见[当时的报告](report-ecs-release-20260927.md#原始证据与生产恢复)，异机 S2 JSON 另存于 Git 忽略的 `bench/runs/`；原始证据不入库。

两次 2026-09-28 维护窗口的客户端与服务端原始归档、SHA-256 分别见[JMeter 报告](report-ecs-jmeter-20260928.md#恢复原始证据与结论边界)与[原工具六场景报告](report-ecs-spectrum-20260928.md#生产恢复证据与剩余边界)；统一路径和哈希还见[私有归档索引](../private-evidence-index.json)。证据保存在 Git 忽略目录及 ECS 私有维护目录，不公开提交。
