# PeerGrab 后端

PeerGrab 的 Java 后端负责任务状态、抢单裁决、资金过账及异步流转。API 与 Worker 分进程，复用同一领域模型和用例；[项目展示、架构图与功能截图](../README.md)位于仓库首页。

## 业务与模块

```text
peergrab-backend/
├── peergrab-shared/          通用值对象、错误码、ID 与消息协议
├── peergrab-domain/          任务状态机、业务规则、端口
├── peergrab-application/     发布、抢单、履约、结算、退款、仲裁用例
├── peergrab-infrastructure/  MySQL、Redis、RocketMQ 与限流适配器
├── peergrab-presentation/    REST、WebSocket、鉴权与 DTO
├── peergrab-bootstrap/       API 进程装配与集成测试
├── peergrab-worker/          延迟流转、自动结算、补偿、校验和对账
├── peergrab-bench/           实验客户端
├── peergrab-sharding-lab/   离线 ShardingSphere 规则实验，不进入 API/Worker 运行包
└── peergrab-txmsg-lab/      离线 RocketMQ 事务消息协议仿真，不进入 API/Worker 运行包
```

发布时资金进入托管；一个任务目前只有一个名额。并发抢单以 Redis Lua 降低冲突、MySQL CAS 和唯一索引作最终裁决。确认、取货、送达后结算；取消走退款，争议由仲裁员处理。`availableActions` 从服务端按身份和状态计算，前端依此显示按钮。雪花 ID 在 JSON 中序列化为字符串，防止 JavaScript 精度损失。

Worker 消费 RocketMQ 延迟及资金事件，并通过数据库扫描、本地消息表和资金事件 outbox 补偿；MQ 不可用时资金本地事务仍可提交。结算、退款和仲裁依赖同库事务、业务号唯一约束与资金对账。当前运行环境是**单 MySQL 数据源、单 ECS 部署**；ShardingSphere 规则和算法测试已隔离在 `peergrab-sharding-lab`，不进入线上分片链路。完整取舍见[架构设计](../docs/architecture/架构设计与技术选型.md)及[生产部署及迁移](docker/ECS_DEPLOY.md)。

## 身份与角色

没有用户表：身份来自 token，仲裁员由配置 `peergrab.auth.arbitrator-id`（默认 `9001`）指定。`ActorRolePolicy` 禁止仲裁员发布任务或抢单，仲裁用例还会再校验一次"仲裁员不得是当事人"。仲裁方向由 `ArbitrationFavor.fromWire` 严格解析 `RUNNER`/`PUBLISHER`，空值、拼写错误一律返回 `INVALID_ARGUMENT`，不会默认判给任一方。登录响应带 `role`（`ARBITRATOR`/`USER`）；登录失败由 `LoginAttemptGuard` 在进程内按 IP+userId 限速（默认 5 次/60 秒窗口、锁 300 秒），多 API 实例时降级为每实例限速。演示口令与 `9001` 仲裁员仅供本机与公开演示，生产须关闭 demo 并接真实身份。

## 可观测性

API 与 Worker 都暴露 `/actuator/prometheus`，含 JVM、HTTP、Hikari、业务结果计数，以及资金 outbox、延迟消息、对账差异和缓存失效失败指标。仓库另提供叠加文件 [`docker/docker-compose.observability.yaml`](docker/docker-compose.observability.yaml)：与基础栈一起启动并加 `--profile observability`，即得到 Prometheus（`127.0.0.1:29090`）与 Grafana（`127.0.0.1:23000`），11 条告警规则覆盖进程存活、outbox 积压与老化、延迟消息 dead、对账差异、双删失败、API 非 OK 比例与 `INTERNAL_ERROR`。使用方法与指标口径见 [observability/README.md](docker/observability/README.md)；边界是暂无 tracing、日志聚合与 Alertmanager。

## 启动全栈演示

需要 Docker 和 Compose v2。在仓库根目录执行：

```bash
cd peergrab-backend/docker
cp -n .env.example .env
docker compose -f docker-compose.yaml -f docker-compose.full.yaml --env-file .env config --quiet
docker compose -f docker-compose.yaml -f docker-compose.full.yaml --env-file .env up -d --build
docker compose -f docker-compose.yaml -f docker-compose.full.yaml --env-file .env ps
```

默认 Web 入口为 `http://127.0.0.1:25173`，API 调试端口为 `28080`；浏览器通过 Nginx 同源访问 `/api` 和 `/ws`。演示账号为 `1001 / demo1001`（发单人）、`2001 / demo2001`、`2002 / demo2002`（跑腿者）、`9001 / demo9001`（仲裁员）。这些公开凭据仅供本机 Compose 演示；正常应用配置默认禁用演示登录。

本机完整演示栈默认使用 Redis Session；[生产栈](docker/ECS_DEPLOY.md)使用 MySQL 真值的 JWT，以便 Redis 故障时仍能校验已签发令牌，并要求至少 32 个 UTF-8 字节的 `PEERGRAB_AUTH_JWT_SECRET`。已有 `peergrab-local` 数据卷升级前须按下节执行迁移；仅重建镜像不会重新运行 `init.sql`。停止演示栈使用相同两个 `-f` 参数执行 `docker compose down`，保留数据时不要加 `-v`。

### 已有本机 PeerGrab 数据卷升级

在 `peergrab-backend/docker` 中，先停写并备份独立的 `peergrab-local` 数据，再执行当前数据库迁移；全新数据卷由 `init.sql` 初始化，无须运行这些脚本。下面的备份目录须位于仓库外并限制访问。若旧库尚未具备 P6 的 `campus_id` 字段，先按[生产迁移说明](docker/ECS_DEPLOY.md)核对并执行 `migrate-p6.sql`。

```bash
docker compose --env-file .env -f docker-compose.yaml -f docker-compose.full.yaml stop app worker
PEERGRAB_COMPOSE_ENV_FILE="$PWD/.env" PEERGRAB_COMPOSE_FILE="$PWD/docker-compose.yaml" \
  ./backup-mysql.sh /path/to/private-backups
docker compose --env-file .env -f docker-compose.yaml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab' < migrate-runtime.sql
docker compose --env-file .env -f docker-compose.yaml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab' < migrate-query-indexes.sql
docker compose --env-file .env -f docker-compose.yaml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab' < migrate-wallet-per-errand.sql
docker compose --env-file .env -f docker-compose.yaml exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab' < migrate-grab-request-v2.sql
docker compose --env-file .env -f docker-compose.yaml -f docker-compose.full.yaml up -d --build
```

2026-10 起抢单 v2 需要 `grab_request` 表；旧卷必须先执行 `migrate-grab-request-v2.sql`，否则 `/grab-v2` 与 Worker 恢复会因缺表报错。

2026-10 起资金账户改为“每任务一对独立 ESCROW/COMMISSION 账户”（`owner_id=errand_id`），`init.sql` 不再预置全局 `-1/-2` 系统户。已有卷必须先执行 `migrate-wallet-per-errand.sql`：它只迁移仍为 `HELD` 的托管单，把旧共享托管户余额转入每单托管户，并补齐每单佣金户；旧 `-1/-2` 行保留作历史流水归属。脚本可重复执行，执行前须停写、备份，在恢复副本上演练。

如果旧环境调整过确认或自动结算期限，执行 `migrate-runtime.sql` 前先按[生产迁移说明](docker/ECS_DEPLOY.md)在同一 MySQL 会话中设置原期限并验证回填。升级后检查 app、worker 的健康状态与 `/actuator/health/readiness`。

2026-09-27 首次在单 ECS 演示站部署 `2370c45`：旧卷先备份、在隔离 MySQL 中恢复并试跑迁移，再停写执行正式迁移。新资金事件使用 `errand-fund-event-v2` 普通 Topic；一笔上线验收交易的 outbox 为 `SENT`、持久通知已落库、消费组积压为 0。S5 压测夹具的截止时间修正见后续提交 `7da0a92`。2026-09-28 生产 API 与 Worker 已切换到 `5ace500`；[2026-11-04 最终评测](bench/reports/peergrab-current/report-20261104-final.md)覆盖当前 HEAD 的读容量、S4 结算、自动结算、S6 故障、S1 抢单与旧 IT 复验。

## 本机开发

需要 JDK 21、Maven 3.9+ 和 Node 20+。基础 Compose 栈提供 MySQL、Redis、RocketMQ；本机默认连接端口分别是 `3307`、`6380`、`8081`。以下命令以基础 Compose 默认端口为例：

```bash
cd peergrab-backend/docker
docker compose --env-file /dev/null up -d mysql redis rmqnamesrv rmqbroker
COMPOSE_ENV_FILE=/dev/null ./init-mq.sh
cd ..
mvn -DskipTests package
export PEERGRAB_AUTH_DEMO_ENABLED=true
export PEERGRAB_AUTH_DEMO_PASSWORD_1001=demo1001
export PEERGRAB_AUTH_DEMO_PASSWORD_2001=demo2001
export PEERGRAB_AUTH_DEMO_PASSWORD_2002=demo2002
export PEERGRAB_AUTH_DEMO_PASSWORD_9001=demo9001
java -jar peergrab-bootstrap/target/peergrab-bootstrap-1.0.0-SNAPSHOT.jar
```

另开终端在 `peergrab-backend` 启动 `java -jar peergrab-worker/target/peergrab-worker-1.0.0-SNAPSHOT.jar`。前端从 `peergrab-frontend` 运行 `npm ci && npm run dev`。四个演示口令须各不相同，正式身份系统不在这个演示实现中。

## 文档与配置口径

工程文档唯一入口见 [`docs/README.md`](../docs/README.md)：[设计基准](../docs/architecture/design-benchmark.md)、[消息一致性](../docs/architecture/messaging-consistency.md)、[分片设计](../docs/architecture/sharding-design.md)、[演进与踩坑](../docs/engineering/design-evolution.md)、[压测方法论](../docs/benchmark/methodology.md)。

两份进程 `application.yaml` 共享的配置键必须保持同一值，由 `peergrab-bootstrap/src/test/java/com/peergrab/config/SharedConfigConsistencyTest.java` 门禁；测试数量以同一条 `mvn -Dpeergrab.it=true test` 命令配合 [`tools/summarize_tests.py`](tools/summarize_tests.py) 输出为准，不在多个文档里各写一套。

## API 与验证

REST 接口保持 `/api/auth`、`/api/errands`、`/api/wallet`、`/api/credit`、`/api/notifications`；`/ws` 提供实时推送。发布任务必须带 `X-Request-Id`：同一发单人用同一键和相同内容重试返回原任务，不重复托管；缺少该头返回 `INVALID_ARGUMENT`，服务端不会随机补键。

```bash
cd peergrab-backend
mvn test
```

默认只运行单测。集成测试会删除流水与托管单并重置账户，**只能连接可丢弃的独立测试 MySQL/Redis**，不能指向已有业务数据：

```bash
export PEERGRAB_TEST_DB_HOST=127.0.0.1 PEERGRAB_TEST_DB_PORT='<独立测试 MySQL 端口>'
export PEERGRAB_TEST_REDIS_HOST=127.0.0.1 PEERGRAB_TEST_REDIS_PORT='<独立测试 Redis 端口>'
export PEERGRAB_TEST_DB_PASSWORD='<独立测试 MySQL 密码>'
mvn -Dpeergrab.it=true test
```

当前基线为 **350 项后端测试、0 失败、0 错误、0 跳过；其中 93 项集成测试真实执行（34 个 IT 报表文件）**（2026-10-28 同一条命令口径）。2026-11-04 在隔离 MySQL/Redis 上复验了 `PublishIdempotencyIT`（1/1）与 `SettleConcurrencyIT`（2/2），覆盖 12 并发同键发布、200 线程同单结算和 500 任务三层对账。测试数量以 [`tools/summarize_tests.py`](tools/summarize_tests.py) 汇总为唯一口径，CI backend job 输出该汇总并按 failures/errors/IT 未跳过门禁。

在可丢弃的本地演示库上可运行 `python3 bench/scripts/smoke_e2e.py --env-file docker/.env`；脚本验证发布、抢单、结算、退款、仲裁和通知，**会创建任务并改变演示账户余额**。只读资金不变式检查可执行：

```bash
docker compose -f docker/docker-compose.yaml --env-file docker/.env exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot peer_grab' \
  < bench/scripts/verify_fund.sql
```

## 实验记录与边界

2026-11-04 最终评测（当前 HEAD `5c1ecec`）是当前唯一基线，完整报告见[最终评测](bench/reports/peergrab-current/report-20261104-final.md)，脱敏摘要见[证据 JSON](bench/reports/peergrab-current/final-2026-11-04-evidence.json)。2026-08/09/10 的旧报告、旧镜像证据和旧工具口径已清理。

| 场景 | 最终结果 | 口径 |
|---|---|---|
| 读容量 | **2450 RPS×60s×3 轮 0 错误**；P99 典型 12.8–14.4ms、最差轮 122ms；2500 RPS 起每轮 2 次超时 | 隔离 bench、8C ECS、HTTP、10k 热页、Hikari 50 |
| S4 结算 | **200/32：218.6 TPS、P99 247.8ms；500/64：387.7 TPS、P99 254.7ms** | 隔离 bench、同机、短批次、生产同款池、多跑腿 |
| 自动结算 | **1s 扫描 + 500/批：P50 3.2s / P95 11.3s / P99 11.3s** | 隔离 bench、scan-only；生产已应用 |
| Redis 故障 | **暂停 25s，250 次详情读 0 错误、P95 107.5ms（100ms 超时）** | 隔离 bench、Python 10 RPS、单次故障；生产已应用 |
| Broker 故障 | **暂停 30s，结算 180ms 返回、DB SETTLED；恢复后 outbox 约 3s 投递、资金差 0** | 隔离 bench、单笔结算 |
| 2,000 线程 | **1 成功、1,996 名额满、3 冲突、0 系统错误；DB slot_taken=1** | 隔离 bench、同机、限流关闭；只证明正确性 |

- Hikari 100 在读容量中反而更差（2450/2500 P99 约 242ms），保持 Hikari 50；读路径优化方向是查询/缓存，不是扩池。
- 生产已应用 `SPRING_DATA_REDIS_TIMEOUT=100ms`、`SETTLE_SCAN_INTERVAL_MS=1000`、`SETTLE_SCAN_BATCH_SIZE=500`；其余生产配置未改。
- 边界：读容量是单接口热页/HTTP 上限；S4 是同机短批次；S6 是单节点单次故障；2,000 线程 P99 含同机发压端开销；均不是稳态 QPS、HA 或全站 SLA。
- 复测须按[压测运行手册](bench/README.md)使用独立环境；`bench/runs/20261104-final/` 只保留本轮私有证据。
