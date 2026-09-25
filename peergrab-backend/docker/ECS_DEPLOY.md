# peergrab.cn 单机部署

适用 Ubuntu 24.04、4 vCPU / 16 GiB 的 ECS。`docker-compose.prod.yaml` 是独立栈，不与本机的两个 Compose 文件叠加。MySQL、Redis、RocketMQ 和 API 只在 Docker 网络内；前端只绑定 ECS 的 `127.0.0.1:25173`，由宿主 Nginx 对外提供 80/443 和 HTTPS。

## 1. 准备

- 在阿里云 DNS 为 `peergrab.cn` 和 `www.peergrab.cn` 设置 A 记录，均指向 ECS 当前公网 IP；确认解析生效。`www` 将跳转到根域。
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

`.env.prod` 已被 Git 忽略；请私下备份其中随机生成的数据库口令，并在保留 MySQL 卷时沿用。首次创建卷后才会执行 `init.sql`，重新生成环境文件不会修改库内密码。四个演示口令与前端预填值一致，供任何访客操作：发单人 1001、跑腿者 2001/2002、仲裁员 9001。所有身份、钱包余额和交易都是公开演示数据，没有真实用户或支付接入。

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
sudo nginx -t
sudo systemctl reload nginx
```

将下面的证书续期钩子保存到 `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh`，设为可执行；确认 Certbot 自动续期计时器启用，再运行 `sudo certbot renew --dry-run`。

```sh
#!/bin/sh
systemctl reload nginx
```

如果改了 `PEERGRAB_WEB_PORT`，同步修改 `nginx/peergrab.conf` 的三个 `proxy_pass` 端口。Certbot 的 [webroot 和续期钩子说明](https://eff-certbot.readthedocs.io/en/stable/using.html)可用于核对证书流程。

## 3. 验收与维护

```bash
curl -fsS https://peergrab.cn/api/health
curl -I https://peergrab.cn/
curl -I https://www.peergrab.cn/
docker compose --env-file .env.prod -f docker-compose.prod.yaml ps
docker compose --env-file .env.prod -f docker-compose.prod.yaml logs --tail=100 app worker
```

用浏览器检查四个预填演示身份的登录、任务广场、任务详情和 `wss://peergrab.cn/ws`。证书须同时匹配根域和 `www`；`www` 的 HTTP/HTTPS 都应 301 到 `https://peergrab.cn`。宿主机 `ss -lntp` 应显示 80/443 对外监听、25173 只监听 `127.0.0.1`；MySQL 3306、Redis 6379、RocketMQ 9876/10911/8081 与 API 8080 不应有宿主机监听。

更新代码时，在该目录执行 `git pull` 和 `docker compose --env-file .env.prod -f docker-compose.prod.yaml up -d --build`。备份三个命名卷与 `.env.prod`；停止栈时用 `docker compose ... down`，**不要加 `-v`**，以免删除业务数据。公开演示只使用虚拟资金，用户可操作的数据应与本机开发、压测环境隔离。
