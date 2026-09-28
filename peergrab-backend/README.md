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
└── peergrab-sharding-lab/   离线 ShardingSphere 规则实验，不进入 API/Worker 运行包
```

发布时资金进入托管；一个任务目前只有一个名额。并发抢单以 Redis Lua 降低冲突、MySQL CAS 和唯一索引作最终裁决。确认、取货、送达后结算；取消走退款，争议由仲裁员处理。`availableActions` 从服务端按身份和状态计算，前端依此显示按钮。雪花 ID 在 JSON 中序列化为字符串，防止 JavaScript 精度损失。

Worker 消费 RocketMQ 延迟及资金事件，并通过数据库扫描、本地消息表和资金事件 outbox 补偿；MQ 不可用时资金本地事务仍可提交。结算、退款和仲裁依赖同库事务、业务号唯一约束与资金对账。当前运行环境是**单 MySQL 数据源、单 ECS 部署**；ShardingSphere 规则和算法测试已隔离在 `peergrab-sharding-lab`，不进入线上分片链路。完整取舍见[架构设计](docs/架构设计与技术选型.md)、[校招／实习讲述与升级门槛](docs/校招实习项目取舍与面试讲述方案-20260927.md)、[三条主线证据卡](docs/校招实习面试证据卡-20260927.md)及[生产部署及迁移](docker/ECS_DEPLOY.md)。

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

本机完整演示栈默认使用 Redis Session；[生产栈](docker/ECS_DEPLOY.md)使用 MySQL 真值的 JWT，以便 Redis 故障时仍能校验已签发令牌，并要求至少 32 个 UTF-8 字节的 `PEERGRAB_AUTH_JWT_SECRET`。已有旧卷升级前必须先执行迁移脚本；仅重建镜像不会重新运行 `init.sql`。[旧项目迁移](docker/UPGRADE.md)另有独立步骤。停止演示栈使用相同两个 `-f` 参数执行 `docker compose down`，保留数据时不要加 `-v`。

2026-09-27 首次在单 ECS 演示站部署 `2370c45`：旧卷先备份、在隔离 MySQL 中恢复并试跑迁移，再停写执行正式迁移。新资金事件使用 `errand-fund-event-v2` 普通 Topic；一笔上线验收交易的 outbox 为 `SENT`、持久通知已落库、消费组积压为 0。S5 压测夹具的截止时间修正见后续提交 `7da0a92`。2026-09-28 生产 API 与 Worker 已切换到 `5ace500`；[当前镜像 ECS 六类场景实测](bench/reports/peergrab-current/report-ecs-spectrum-20260928.md)覆盖完整 HTTPS 首屏、并发抢单、缓存、结算、到期处理及受限故障注入。

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

## API 与验证

REST 接口保持 `/api/auth`、`/api/errands`、`/api/wallet`、`/api/credit`、`/api/notifications`；`/ws` 提供实时推送。发布任务可带 `X-Request-Id`：同一发单人用同一键和相同内容重试返回原任务，不重复托管；未提供键时每次请求都是新发布。

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

2026-09-27 的旧版在可丢弃 MySQL/Redis 上运行 **214 项后端测试，0 失败、0 跳过**；前端 `npm run build` 与后端 `mvn -DskipTests package` 通过。真实 RocketMQ 的普通 Topic 类型、发送和消费读回另经独立契约脚本验证。2026-09-28 优化版在全新隔离 MySQL/Redis 上运行 `mvn -Dpeergrab.it=true test`，按 Maven 各模块汇总 **223 项执行、0 失败、0 错误、0 跳过**；独立栈的 S1/S4/S5 运行验收见下文报告。

在可丢弃的本地演示库上可运行 `python3 bench/scripts/smoke_e2e.py --env-file docker/.env`；脚本验证发布、抢单、结算、退款、仲裁和通知，**会创建任务并改变演示账户余额**。只读资金不变式检查可执行：

```bash
docker compose -f docker/docker-compose.yaml --env-file docker/.env exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot peer_grab' \
  < bench/scripts/verify_fund.sql
```

## 实验记录与边界

[当前镜像 ECS 六类场景实测](bench/reports/peergrab-current/report-ecs-spectrum-20260928.md)以已部署的 `5ace500` API／Worker 为对象，停站备份后逐场景建立可丢弃的 MySQL、Redis、RocketMQ 卷。S2 广场游标首屏走异机公网 HTTPS，600 RPS × 180 秒三轮各发出 108,000 次，客户端 P99 为 **46.919／52.332／47.839 ms**；第三轮有 **4 次传输超时**，所以尚无该档零错误稳态容量结论。S1 两轮各 2,000 个虚拟客户端同单抢一名额，均只成功 1 人且 0 超卖，但请求 P99 **4.682／5.081 秒**。S4 共享钱包热点 200 单／32 线程短批次两轮持久化结算 **39.52／36.09 TPS**，P99 **1.446／1.483 秒**，资金校验通过。

S3 缓存开启后热态详情回源为 0，三个批次累计 MySQL `Com_select` 较关闭缓存约少 **49.5%**，但首次冷态 P99 更高，不能宣称稳定吞吐提升。S5 两个独立栈分别处理 1,000 单同刻到期，MQ／扫描兜底的到期至状态日志 P99 为 **9.512／25.100 秒**，均最终排空。S6 的 Redis 暂停轮详情读 250/250 成功、P95 **2.013 秒**；Broker 暂停时，默认配置的一次结算已经在数据库提交，却触发客户端 **8 秒超时**。另起新栈仅关闭延迟双删后，结算在 Broker 暂停期间约 **0.095 秒**返回，这只是定位对照，线上默认配置尚未修复。六类负载的计时起止与发压模型不同，不能将短批次 TPS、秒级尖峰和 HTTP 首屏 RPS 合成全站 SLA。

[JMeter 校招／实习简历指标卡](docs/校招实习简历性能指标-JMeter-20260928.md)给出本次新轮的可追溯写法。[原工具指标卡](docs/校招实习简历性能指标-20260928.md)、[2026-09-27 优化前简历基线](bench/reports/peergrab-current/report-resume-metrics-20260927.md)和[旧版指标卡](docs/校招实习简历性能指标-20260927.md)单独保留；代码、路径、工具和窗口不同，不能据此计算优化增益。[高延迟与超时定位报告](docs/高延迟与超时定位报告-20260928.md)、[优化版单轮复测](docs/性能优化与复测-20260928.md)记录阶段性分析。复测须按[压测运行手册](bench/README.md)使用独立环境，不能作用于演示库。

当前 HTTP 压测统一使用 [JMeter 5.6.3 场景计划](bench/jmeter/README.md)，[ECS JMeter 复测](bench/reports/peergrab-current/report-ecs-jmeter-20260928.md)与前述原工具历史数字分开：广场游标首屏 500 目标 RPS × 180 秒 × 3 轮实际 269,608 次全部通过，最差轮 P99 255 ms；600 目标 RPS 长档第二轮有 1 次连接超时。JMeter 2,000 用户线程抢单恰好 1 人成功、0 超卖，但请求开始横跨 2.228 秒；结算 200 单／32 线程短批次 34.72 持久化 TPS，资金校验通过。两种工具不能直接比较收益。[可观测性现状评估](docs/可观测性现状评估-20260928.md)说明 API/Worker 已暴露 Prometheus 指标，但生产尚无持续看板、告警或分布式追踪。

当前公开 API 限制 `slotTotal=1`；完整故障注入矩阵、故障恢复容量、多库资金方案仍待验证。ES/Canal 已退出运行架构，缓存一致性靠失效、TTL 与校验任务，不使用 binlog 秒级纠偏。[设计演进](docs/设计演进记录.md)、[分片取舍](docs/数据库分片相关思考.md)和[压测实验方案](docs/压测方案与容量评估.md)保存了相关设计和历史证据。
