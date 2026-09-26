# PeerGrab 后端

PeerGrab 的 Java 后端负责任务状态、抢单裁决、资金过账及异步流转。API 与 Worker 分进程，复用同一领域模型和用例；[项目展示、架构图与功能截图](../README.md)位于仓库首页。

## 业务与模块

```text
peergrab-backend/
├── peergrab-shared/          金额（分）、错误码、结果、雪花 ID
├── peergrab-domain/          任务状态机、业务规则、端口
├── peergrab-application/     发布、抢单、履约、结算、退款、仲裁用例
├── peergrab-infrastructure/  MySQL、Redis、RocketMQ 与限流适配器
├── peergrab-presentation/    REST、WebSocket、鉴权与 DTO
├── peergrab-bootstrap/       API 进程装配与集成测试
├── peergrab-worker/          延迟流转、自动结算、补偿、校验和对账
└── peergrab-bench/           实验客户端
```

发布时资金进入托管；一个任务目前只有一个名额。并发抢单以 Redis Lua 降低冲突、MySQL CAS 和唯一索引作最终裁决。确认、取货、送达后结算；取消走退款，争议由仲裁员处理。`availableActions` 从服务端按身份和状态计算，前端依此显示按钮。雪花 ID 在 JSON 中序列化为字符串，防止 JavaScript 精度损失。

Worker 消费 RocketMQ 延迟及资金事件，并通过数据库扫描和本地消息表补偿。结算、退款和仲裁依赖同库事务、业务号唯一约束与三层资金对账。当前运行环境是**单 MySQL 数据源**；ShardingSphere 规则和算法测试不是线上分片链路。完整取舍见[架构设计](docs/架构设计与技术选型.md)与[当前架构评估](docs/项目架构与技术选型评估.md)。

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

认证默认使用 Redis Session。如将 `.env` 中 `PEERGRAB_AUTH_MODE` 设为 `jwt`，还需设置至少 32 个 UTF-8 字节的 `PEERGRAB_AUTH_JWT_SECRET`。已有旧卷先按[升级说明](docker/UPGRADE.md)迁移；升级时不能仅重建镜像，因为 `init.sql` 不会在已有数据库卷上重跑。停止演示栈使用相同两个 `-f` 参数执行 `docker compose down`，保留数据时不要加 `-v`。

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

在可丢弃的本地演示库上可运行 `python3 bench/scripts/smoke_e2e.py --env-file docker/.env`；脚本验证发布、抢单、结算、退款、仲裁和通知，**会创建任务并改变演示账户余额**。只读资金不变式检查可执行：

```bash
docker compose -f docker/docker-compose.yaml --env-file docker/.env exec -T mysql \
  sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot peer_grab' \
  < bench/scripts/verify_fund.sql
```

## 实验记录与边界

[8 vCPU／100 Mbps ECS 复测](bench/reports/peergrab-current/report-ecs-8cpu-20260927.md)记录任务广场完整公网路径拐点、结算 TPS 和抢单正确性；此前的[4 vCPU／50 Mbps 实测](bench/reports/peergrab-current/report-ecs-max-20260926.md)还覆盖缓存与自然到期。两轮及[原版 P6/P7 报告](bench/reports/original-project/report-P6-P7-20260822-complete.md)分开归档。复测从[压测运行手册](bench/README.md)建立独立环境；造数、加压和 `cleanup.sh` 不得作用于演示库。

当前公开 API 限制 `slotTotal=1`；Redis 或 MQ 故障注入后的完整性能恢复、多库资金方案仍待验证。ES/Canal 已退出运行架构，缓存一致性靠失效、TTL 与校验任务，不使用 binlog 秒级纠偏。[设计演进](docs/设计演进记录.md)、[分片取舍](docs/数据库分片相关思考.md)和[压测实验方案](docs/压测方案与容量评估.md)保存了相关设计和历史证据。
