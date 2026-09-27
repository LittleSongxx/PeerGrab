# www.peergrab.cn 单机部署

适用 Ubuntu 24.04、4 vCPU / 16 GiB 的 ECS。`docker-compose.prod.yaml` 是独立栈，不与本机的两个 Compose 文件叠加。MySQL、Redis、RocketMQ 和 API 只在 Docker 网络内；前端只绑定 ECS 的 `127.0.0.1:25173`，由宿主 Nginx 对外提供 80/443 和 HTTPS。

## 1. 准备

- 在阿里云 DNS 为 `peergrab.cn` 和 `www.peergrab.cn` 设置 A 记录，均指向 ECS 当前公网 IP；确认解析生效。根域将跳转到 `www`。
- 安全组和主机防火墙允许 TCP 80/443。管理用 SSH 端口只向可信来源开放。确认 ECS 上 80/443 由宿主 Nginx 监听、25173 尚未占用，且磁盘有足够空间存储镜像和三个数据卷。
- 安装 Docker Engine、Compose v2、Nginx 和 Certbot。已有其他站点时，先备份并按迁移安排处理其服务及虚拟主机，避免重复的 `server_name peergrab.cn`。

在项目的 `peergrab-backend/docker` 目录执行：

```bash
umask 077
{
  printf 'COMPOSE_PROJECT_NAME=peergrab-prod\nPEERGRAB_WEB_PORT=25173\n'
  for key in PEERGRAB_MYSQL_ROOT_PASSWORD PEERGRAB_MYSQL_APP_PASSWORD; do
    printf '%s=%s\n' "$key" "$(openssl rand -hex 24)"
  done
  printf 'PEERGRAB_AUTH_JWT_SECRET=%s\n' "$(openssl rand -hex 48)"
  printf 'PEERGRAB_AUTH_DEMO_PASSWORD_1001=demo1001\n'
  printf 'PEERGRAB_AUTH_DEMO_PASSWORD_2001=demo2001\n'
  printf 'PEERGRAB_AUTH_DEMO_PASSWORD_2002=demo2002\n'
  printf 'PEERGRAB_AUTH_DEMO_PASSWORD_9001=demo9001\n'
} > .env.prod
docker compose --env-file .env.prod -f docker-compose.prod.yaml config --quiet
docker compose --env-file .env.prod -f docker-compose.prod.yaml up -d --build
docker compose --env-file .env.prod -f docker-compose.prod.yaml ps
curl -fsS http://127.0.0.1:25173/api/health
```

`.env.prod` 已被 Git 忽略；请私下备份数据库口令和 JWT 密钥，并在保留 MySQL 卷时沿用。JWT 密钥更换会让既有 access token 失效。首次创建卷后才会执行 `init.sql`，重新生成环境文件不会修改库内密码。四个演示口令与前端预填值一致，供任何访客操作：发单人 1001、跑腿者 2001/2002、仲裁员 9001。所有身份、钱包余额和交易都是公开演示数据，没有真实用户或支付接入。

## 2. 签发证书并开放站点

先把仓库中的 `nginx/peergrab-bootstrap.conf` 安装到宿主 Nginx。它只响应 ACME 验证；证书尚未签发时不会开放应用。

```bash
sudo install -d -m 755 /var/www/letsencrypt/.well-known/acme-challenge
sudo install -m 644 nginx/peergrab-bootstrap.conf /etc/nginx/sites-available/peergrab.cn
sudo ln -sfn /etc/nginx/sites-available/peergrab.cn /etc/nginx/sites-enabled/peergrab.cn
sudo nginx -t
sudo systemctl reload nginx
sudo certbot certonly --webroot -w /var/www/letsencrypt \
  -d peergrab.cn -d www.peergrab.cn \
  --email '<你的邮箱>' --agree-tos --no-eff-email
sudo install -m 644 nginx/peergrab.conf /etc/nginx/sites-available/peergrab.cn
sudo install -m 644 nginx/peergrab-default-deny.conf /etc/nginx/sites-available/peergrab-default-deny.conf
sudo ln -sfn /etc/nginx/sites-available/peergrab-default-deny.conf /etc/nginx/sites-enabled/peergrab-default-deny.conf
sudo nginx -t
sudo systemctl reload nginx
```

默认站点拒绝未配置的域名，避免已移除项目的旧 DNS 记录落到 PeerGrab 页面。

将下面的证书续期钩子保存到 `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`，设为可执行；确认 Certbot 自动续期计时器启用，再运行 `sudo certbot renew --dry-run`。

```sh
#!/bin/sh
systemctl reload nginx
```

如果改了 `PEERGRAB_WEB_PORT`，同步修改 `nginx/peergrab.conf` 中 `peergrab_frontend` 上游的端口。Certbot 的 [webroot 和续期钩子说明](https://eff-certbot.readthedocs.io/en/stable/using.html)可用于核对证书流程。

## 3. 验收与维护

```bash
curl -fsS https://www.peergrab.cn/api/health
curl -I https://www.peergrab.cn/
curl -I https://peergrab.cn/
docker compose --env-file .env.prod -f docker-compose.prod.yaml ps
docker compose --env-file .env.prod -f docker-compose.prod.yaml logs --tail=100 app worker
```

用浏览器检查四个预填演示身份的登录、任务广场、任务详情和 `wss://www.peergrab.cn/ws`。证书须同时匹配根域和 `www`；根域的 HTTP/HTTPS 都应 301 到 `https://www.peergrab.cn`。宿主机 `ss -lntp` 应显示 80/443 对外监听、25173 只监听 `127.0.0.1`；MySQL 3306、Redis 6379、RocketMQ 9876/10911/8081 与 API 8080 不应有宿主机监听。

更新代码前先执行 `./backup-mysql.sh /path/to/private-backups`，在独立 MySQL 容器中试恢复生成的 `peer_grab.sql.gz` 并执行 `bench/scripts/verify_fund.sql`；只看到备份文件存在不等于可恢复。备份目录还包含 `.env.prod` 的私有副本，必须限制访问并复制到 ECS 之外。Redis 中的会话会在丢卷后失效；Broker 丢卷后，任务超时/自动结算由数据库扫描补偿，资金事件的 `SENT` 记录不会因 Broker 丢卷自动重发，故仍需单独备份 Broker store 或对通知做重建。

已有数据库卷**不会重跑** `init.sql`。这次版本引入资金事件 outbox、跑腿额度锁、扫描重试表、雪花节点租约、MySQL JWT 会话表和查询索引；必须先停写、备份，再在旧卷上执行迁移，最后启动新镜像。现有 `.env.prod` 还须加入至少 32 字节的 `PEERGRAB_AUTH_JWT_SECRET`（可用 `openssl rand -hex 48` 生成）。生产鉴权从 Redis Session 切换为 MySQL 真值的 JWT 后，旧 Session 不迁移，用户需重新登录：

资金事件从 RocketMQ 事务消息改成 MySQL outbox 后，发送方使用普通消息。新版本使用 `errand-fund-event-v2`（`NORMAL`），保留旧 `errand-fund-event`（`TRANSACTION`）及其 Broker 数据，不能删除旧 Topic 来“改类型”。停写后先检查旧组 `peergrab-fund-event-consumer` 和 `peergrab-fund-event-push` 在旧 Topic 的积压及重试消息，待积压消费完并保存 `consumerProgress`；如旧事件未处理完，应先让旧消费者排空或制定按 `biz_no` 幂等补发方案，再切换。新 `mq-init` 会创建 v2 Topic。启动新 Worker 后，用一笔隔离验收交易核对 `fund_event_outbox.status=SENT`、v2 Topic 消费进度和 `notification`；旧 Topic 留待保存期届满并核对后单独退役。回滚需要恢复旧版本进程与旧消费组位点，保留两代 Topic，不能把 v2 的消费位点套在旧 Topic 上。

迁移会给旧 `LOCKED` 和 `DELIVERED` 任务固化确认、自动结算截止时间，默认窗口分别为 300 和 86400 秒。如果旧环境使用其他窗口，执行 `migrate-runtime.sql` 前须在**同一 MySQL 会话**设置 `@peergrab_confirm_seconds`、`@peergrab_auto_seconds`。应先在恢复副本核对回填任务数和到期行为，再应用于旧卷。

```bash
compose=(docker compose --env-file .env.prod -f docker-compose.prod.yaml)
"${compose[@]}" stop frontend app worker
./backup-mysql.sh /path/to/private-backups
"${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab' < migrate-runtime.sql
"${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab' < migrate-query-indexes.sql
"${compose[@]}" run --rm --no-deps mq-init
"${compose[@]}" up -d --build app worker frontend
"${compose[@]}" ps
```

旧环境使用自定义期限时，将上面执行 `migrate-runtime.sql` 的一行替换为下面的**同一连接输入流**，把秒数换成旧版运行配置值：

```bash
{ printf 'SET @peergrab_confirm_seconds=600; SET @peergrab_auto_seconds=43200;\n'; cat migrate-runtime.sql; } | \
  "${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot peer_grab'
```

重复执行迁移脚本应保持安全；正式执行前仍要在可丢弃的恢复副本上走一遍。停止栈时用 `docker compose ... down`，**不要加 `-v`**。公开演示只使用虚拟资金，压测必须使用独立库和卷。

容器就绪检查现在访问内部 `/actuator/health/readiness`：生产 API 核验 MySQL，Worker 另核验 Redis 和调度心跳；公开 `/api/health` 仍只表示进程存活。`/actuator/prometheus` 仅在容器网络内提供，包括 JVM、HTTP、Hikari、`Result.code` 业务结果计数，以及资金 outbox、延迟消息、对账差异和缓存失效失败指标。建议至少告警：就绪失败、资金 outbox 最老 PENDING 持续增长、到期任务处理滞后、对账差异非零，并定期做独立恢复演练，实测记录 RPO/RTO 后再承诺可用性目标。

## 4. 多主机部署边界

`docker-compose.prod.yaml` 仍是单 ECS 演示栈；同机把 app 扩为两个副本只能缓解进程故障，不能抵御主机或磁盘故障。仓库另提供 [`docker-compose.distributed.yaml`](docker-compose.distributed.yaml)：在至少两台主机分别运行 app、worker 和 frontend，外部负载均衡器只接入健康主机；各主机共用同一个 MySQL 主库、Redis 和 RocketMQ 集群，以及相同 JWT 密钥。所有主机应使用同一提交构建出的镜像摘要。新环境必须先在共享 MySQL 执行迁移，再启动应用。

跨副本任务 ID 由 MySQL 租约分配；WebSocket 事件经 Redis Pub/Sub 分发，客户端定期从 MySQL 补读；Worker 消费与扫描按幂等/领取租约处理。Redis Pub/Sub 本身不存历史，Redis 故障期间实时事件可丢但资金和站内通知仍以 MySQL 为准。外部数据库、缓存、MQ 和负载均衡器的高可用及备份不由这个 Compose 文件创建，实际 RPO/RTO 须在目标环境的故障演练中验证。
