# PeerGrab 最小可观测性栈（Prometheus + Grafana）

> 该 overlay 只面向本地/演示栈。不要直接叠加到生产栈：Grafana 默认匿名访问且口令为本地占位值，
> Prometheus 也是无鉴权的单实例 TSDB。生产接入前必须先改鉴权、网络暴露范围与保留策略。

本目录提供一套**最小可用**的本机可观测性栈：Prometheus 抓取 app / worker 的
`/actuator/prometheus`，加载 11 条告警规则，并用一个 9 面板的 Grafana dashboard
覆盖 API 业务结果、抢单阶段延迟、资金 / 延迟消息 outbox 积压、对账差异、进程与 JVM 资源。

它**不修改** `docker-compose.yaml` 与 `docker-compose.full.yaml` 中的任何服务定义，
只通过叠加文件新增 `prometheus` 与 `grafana` 两个服务，且都挂在 `observability` profile 下。

## 目录结构

```
peergrab-backend/docker/
├── docker-compose.observability.yaml        # 叠加文件（必须与基础栈一起用）
└── observability/
    ├── prometheus.yml                       # 抓取配置（app:8080 / worker:8082）
    ├── alerts.yml                           # 11 条告警规则
    ├── grafana/
    │   ├── provisioning/datasources/datasource.yml
    │   ├── provisioning/dashboards/dashboard.yml
    │   └── dashboards/peergrab.json         # 9 面板 dashboard
    └── README.md
```

## 启动

在 `peergrab-backend/docker` 目录下、并且已经按基础栈要求准备好 `.env` 后执行：

```bash
docker compose -p peergrab-local --env-file .env \
  -f docker-compose.yaml -f docker-compose.full.yaml -f docker-compose.observability.yaml \
  --profile observability up -d
```

要点：

- **三个 `-f` 必须一起给**。`docker-compose.observability.yaml` 是叠加文件，单独使用没有
  app / worker 服务，Prometheus 的抓取目标会一直 `down`。
- **必须带 `--profile observability`**。两个新服务都只声明了这个 profile，不带 profile 时
  它们不会被创建，基础栈行为与原来完全一致。
- 只新增容器，不需要 `--build`；第一次启动会拉取 `prom/prometheus` 与 `grafana/grafana` 镜像。

停止（保留 Prometheus / Grafana 数据卷）：

```bash
docker compose -p peergrab-local --env-file .env \
  -f docker-compose.yaml -f docker-compose.full.yaml -f docker-compose.observability.yaml \
  --profile observability down
```

需要在 `.env` 里覆盖的可选项（都有默认值，不配也能跑）：

| 变量 | 默认值 | 作用 |
| --- | --- | --- |
| `PEERGRAB_PROMETHEUS_PORT` | `29090` | Prometheus 宿主机端口 |
| `PEERGRAB_GRAFANA_PORT` | `23000` | Grafana 宿主机端口 |
| `PEERGRAB_GRAFANA_ADMIN_USER` | `admin` | Grafana 管理员用户名 |
| `PEERGRAB_GRAFANA_ADMIN_PASSWORD` | `peergrab-local-grafana` | Grafana 管理员密码 |
| `PEERGRAB_GRAFANA_ANONYMOUS_ENABLED` | `true` | 是否允许匿名只读访问 |

## 访问地址

两个端口都只绑定 `127.0.0.1`（与基础栈的其它端口保持一致），不对局域网暴露。

- **Prometheus**：<http://127.0.0.1:29090>
  - `/targets` 看 `peergrab-app` / `peergrab-worker` 是否 UP
  - `/alerts` 看当前触发的告警
  - `/rules` 看规则加载与求值状态
- **Grafana**：<http://127.0.0.1:23000>
  - 匿名访问默认开启（Viewer），也可以 `admin` + `PEERGRAB_GRAFANA_ADMIN_PASSWORD` 登录
  - 看板位于 `PeerGrab` 文件夹：**PeerGrab 概览（最小可观测性）**

## 抓取配置

`prometheus.yml` 用 15s 的 `scrape_interval` / `evaluation_interval`，两个 job：

| job | target | metrics_path |
| --- | --- | --- |
| `peergrab-app` | `app:8080` | `/actuator/prometheus` |
| `peergrab-worker` | `worker:8082` | `/actuator/prometheus` |

`management.endpoints.web.exposure.include` 已经是 `health,prometheus`（见
`peergrab-bootstrap` 与 `peergrab-worker` 的 `application.yaml`），management 端口复用
`server.port`，所以容器内 8080 / 8082 就能抓到；这些端口**没有**映射到宿主机，
Prometheus 是通过 compose 默认网络访问服务名的。

## 指标覆盖范围

指标名按 Micrometer 的 Prometheus 命名转换：点号变下划线、Counter 追加 `_total`、
Timer 变成 `<name>_seconds`（带 `publishPercentileHistogram()` 的还会输出 `_bucket`）。
下表的 Prometheus 名称已用仓库内真实抓取样例
`peergrab-backend/bench/runs/codex-maintenance/mixed-long-fast-final-app.prom` 核对过。

| 源码 Meter | Prometheus 指标 | 来源 |
| --- | --- | --- |
| `peergrab.api.results` | `peergrab_api_results_total{code="OK"}` | `ResultMetricsAdvice`（有 `code` 标签） |
| `peergrab.grab.phase` | `peergrab_grab_phase_seconds_bucket{stage=...}` | `GrabPhaseMetrics`（histogram） |
| `peergrab.fund.outbox.pending` | `peergrab_fund_outbox_pending` | worker `WorkerOperationalMetrics` |
| `peergrab.fund.outbox.oldest.age.seconds` | `peergrab_fund_outbox_oldest_age_seconds` | worker `WorkerOperationalMetrics` |
| `peergrab.delay.outbox.pending` | `peergrab_delay_outbox_pending` | worker `WorkerOperationalMetrics` |
| `peergrab.delay.outbox.dead` | `peergrab_delay_outbox_dead` | worker `WorkerOperationalMetrics` |
| `peergrab.recon.diffs.today` | `peergrab_recon_diffs_today` | worker `WorkerOperationalMetrics` |
| `peergrab.cache.double_delete.send.failed` | `peergrab_cache_double_delete_send_failed_total` | `RocketMqCacheEvictAdapter` |

另外还直接用到了 Spring Boot / Micrometer 内置的 JVM 与进程指标：
`process_cpu_usage`、`jvm_memory_used_bytes`、`http_server_requests_seconds`（summary）。

注意 `app` 与 `worker` 的指标并不相同：资金 / 延迟消息 / 对账这类积压 gauge 只在
worker 进程注册，所以这两类告警依赖 `peergrab-worker` job 在线。

## 告警覆盖范围

`alerts.yml` 共 6 组 11 条规则，每条都带 `severity`（`critical` / `warning`）和中文
`summary` / `description`：

| 告警 | 表达式要点 | for | severity |
| --- | --- | --- | --- |
| `PeerGrabAppDown` | `up{job="peergrab-app"} == 0`（readiness 同端口） | 1m | critical |
| `PeerGrabWorkerDown` | `up{job="peergrab-worker"} == 0` | 2m | warning |
| `PeerGrabTargetMissing` | `absent(up{job=~"peergrab-(app\|worker)"})` | 5m | warning |
| `PeerGrabFundOutboxBacklog` | `peergrab_fund_outbox_pending > 0` | 5m | warning |
| `PeerGrabFundOutboxAging` | `peergrab_fund_outbox_oldest_age_seconds > 300` | 5m | critical |
| `PeerGrabDelayOutboxBacklog` | `peergrab_delay_outbox_pending > 5000` | 10m | warning |
| `PeerGrabDelayOutboxDead` | `peergrab_delay_outbox_dead > 0` | 5m | critical |
| `PeerGrabReconDiffDetected` | `peergrab_recon_diffs_today > 0` | 5m | critical |
| `PeerGrabCacheDoubleDeleteSendFailures` | `sum(rate(peergrab_cache_double_delete_send_failed_total[5m])) > 0` | 5m | warning |
| `PeerGrabApiBusinessFailureRatioHigh` | 非 OK 占比（`code!="OK"` 的 rate 之比）`> 0.5` | 10m | warning |
| `PeerGrabApiInternalError` | `sum(rate(peergrab_api_results_total{code="INTERNAL_ERROR"}[5m])) > 0` | 5m | critical |

阈值只是起点，需要按实际负载校准，尤其是：

- 抢单是 N 抢 1 的高失败率场景，`SLOT_FULL` / `GRAB_CONFLICT` 都是**正常业务结果**，
  所以"非 OK 占比"只能当粗粒度哨兵，压测时需要上调；
- `peergrab_delay_outbox_pending` 包含正常等待中的定时消息，绝对值随发布速率线性增长，
  5000 这个阈值只适用于本机演示规模。

## Dashboard 面板（9 个）

1. API 业务结果速率（按 `code`，`rate(peergrab_api_results_total[1m])`）
2. API 非 OK 结果占比（stat，带 30% / 50% 阈值色）
3. 抢单阶段 P99（`histogram_quantile(0.99, ... peergrab_grab_phase_seconds_bucket)`，按 `stage`）
4. HTTP 平均响应时间（`http_server_requests_seconds` 是 summary，只能按 sum/count 求均值）
5. 资金 outbox 积压与老化（PENDING 条数 + 最老年龄秒数）
6. 延迟消息 outbox（PENDING / DEAD）
7. 当日对账差异（stat，非 0 变红）
8. 进程 CPU 使用率（`process_cpu_usage`，app + worker）
9. JVM 堆内存使用量（`jvm_memory_used_bytes{area="heap"}`，按 job / pool）

Dashboard 由文件 provisioning 加载，改 `peergrab.json` 后最多 30s 自动重载；
在 UI 里的修改不会落盘，以文件为准。

## 边界说明

- **目前没有 tracing**：没有接入 OpenTelemetry / Jaeger / Tempo，跨进程（app -> MQ -> worker）
  的调用链无法串联，只能靠阶段 Timer（如 `peergrab_grab_phase`）与日志做间接定位。
- **目前没有日志聚合**：没有 Loki / ELK / 日志采集 agent，日志仍然只能用
  `docker compose logs app worker` 看，非容器日志不在覆盖范围内。
- **没有 Alertmanager**：告警只在 Prometheus 的 `/alerts` 页面上可见，不会发邮件 / IM，
  也没有分组、抑制与静默能力。
- **没有高可用与长期存储**：单实例 Prometheus + 本地 TSDB，保留 7 天
  （`--storage.tsdb.retention.time=7d`），进程或数据卷丢失后历史指标不可恢复。
- **只面向本机开发**：两个端口都绑 `127.0.0.1`，Grafana 默认匿名只读；放到共享环境前
  必须关闭匿名访问、设置强密码，并按需补 TLS / 反向代理。
- **指标只有 /actuator/prometheus 已暴露的部分**：新增业务指标需要在代码里注册
  Micrometer meter，本栈不会自动发现日志或数据库里的状态。

## 本地校验（不启动长期服务）

```bash
# 1) YAML 语法
cd peergrab-backend/docker
python3 -c 'import yaml,glob; [yaml.safe_load(open(f)) for f in glob.glob("observability/**/*.yml",recursive=True)]; print("yaml ok")'

# 2) Prometheus 配置 + 告警规则（一次性容器，跑完即退）
docker run --rm --network none \
  -v "$PWD/observability:/etc/prometheus:ro" \
  --entrypoint promtool prom/prometheus:v3.13.2 \
  check config /etc/prometheus/prometheus.yml

# 3) compose 叠加是否成立（不启动容器）
docker compose -p peergrab-obs-check --env-file .env.example \
  -f docker-compose.yaml -f docker-compose.full.yaml -f docker-compose.observability.yaml \
  --profile observability config --quiet

# 4) dashboard JSON
python3 -c 'import json; json.load(open("observability/grafana/dashboards/peergrab.json")); print("json ok")'
```

## 排障

- `/targets` 里两个目标都是 `down`：确认启动命令带了 `--profile observability`，并且
  三个 compose 文件都在；再确认 app / worker 容器已经 healthy。
- 单个目标 `down`：`docker compose logs prometheus` 看抓取错误；容器内可以
  `docker compose exec app sh -c "curl -fsS http://127.0.0.1:8080/actuator/prometheus | head"`
  自查端点是否正常。
- Grafana 面板显示 "No data"：先确认 Prometheus 能查到同一个指标，再检查时间范围；
  数据源 uid 固定为 `peergrab-prometheus`，改数据源名时不要改 uid。
- 告警一直 `pending` 不触发：`for` 最短 1 分钟、最长 10 分钟，属正常现象。
