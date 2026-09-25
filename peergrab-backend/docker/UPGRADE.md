# 从已有本机栈迁移到 PeerGrab

本指南只适用于原 Compose 项目 campus-dash-local。新项目使用 peergrab-local、数据库 peer_grab 和独立命名卷。旧数据库 campus_dash、旧容器和旧卷不会被原地改名。业务 Topic errand-* 保持原名；新消费组必须继承旧组的位点。迁移期间暂停对外服务。

以下命令在 peergrab-backend/docker 运行。开始前保存完整工作区和旧 Git 历史到仓库外，并确认磁盘足够容纳 MySQL、Redis、RocketMQ 三个卷及 SQL 转储。备份含用户数据和口令，请保持私有。**不要运行 docker compose down -v，也不要删除旧卷。**

## 1. 停写、转储与卷备份

先核对旧项目名和容器名称。此示例保留旧容器，只停止属于 campus-dash-local 的服务。若某个容器或卷不存在，停止迁移并按实际环境查明原因。

~~~bash
set -euo pipefail
cd peergrab-backend/docker
old_project=campus-dash-local
backup=$(mktemp -d /tmp/peergrab-upgrade.XXXXXX)
chmod 700 "$backup"
cp -p .env "$backup/old.env"

old_mysql_volume=$(docker inspect "$old_project-mysql-1" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/mysql"}}{{.Name}}{{end}}{{end}}')
old_redis_volume=$(docker inspect "$old_project-redis-1" --format '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}')
old_broker_volume=$(docker inspect "$old_project-rmqbroker-1" --format '{{range .Mounts}}{{if eq .Destination "/home/rocketmq/store"}}{{.Name}}{{end}}{{end}}')
test -n "$old_mysql_volume" && test -n "$old_redis_volume" && test -n "$old_broker_volume"

docker stop "$old_project-frontend-1" "$old_project-app-1" "$old_project-worker-1"
docker exec "$old_project-mysql-1" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF campus_dash' \
  > "$backup/campus_dash.sql"
test -s "$backup/campus_dash.sql"

docker exec "$old_project-mysql-1" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -e "SELECT table_name FROM information_schema.tables WHERE table_schema='\''campus_dash'\'' AND table_type='\''BASE TABLE'\'' ORDER BY table_name"' \
  > "$backup/tables.txt"
while IFS= read -r table; do
  docker exec "$old_project-mysql-1" sh -c \
    "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot -N campus_dash -e \"SELECT '$table', COUNT(*) FROM $table\"" \
    >> "$backup/old-counts.tsv"
done < "$backup/tables.txt"
docker exec -i "$old_project-mysql-1" sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot campus_dash' \
  < ../bench/scripts/verify_fund.sql > "$backup/old-funds.txt"
docker exec "$old_project-redis-1" redis-cli DBSIZE > "$backup/old-redis-dbsize.txt"
docker exec "$old_project-redis-1" redis-cli SAVE

for mapping in \
  'dash-timeout-consumer errand-confirm-timeout' \
  'dash-autosettle-consumer errand-auto-settle' \
  'dash-fund-event-consumer errand-fund-event' \
  'dash-cache-evict-consumer errand-cache-evict' \
  'dash-fund-event-push errand-fund-event'; do
  read -r group topic <<< "$mapping"
  docker exec "$old_project-rmqbroker-1" sh -c \
    'for x in /home/rocketmq/rocketmq-*/bin/mqadmin; do exec sh "$x" consumerProgress -n rmqnamesrv:9876 -g "$1" -t "$2"; done' \
    _ "$group" "$topic" > "$backup/$group.progress.txt"
done

docker stop "$old_project-rmqbroker-1" "$old_project-rmqnamesrv-1" "$old_project-redis-1" "$old_project-mysql-1"
for mapping in \
  "$old_mysql_volume mysql-volume.tgz" \
  "$old_redis_volume redis-volume.tgz" \
  "$old_broker_volume broker-volume.tgz"; do
  read -r volume archive <<< "$mapping"
  docker run --rm --user 0:0 -v "$volume:/src:ro" -v "$backup:/backup" \
    --entrypoint sh redis:7.4-alpine -c "tar -C /src -czf /backup/$archive ."
done
~~~

确认 SQL 转储、三个归档、表行数及五份消费进度文件都非空，再开始新栈。记录每个 Topic 的 Broker Offset、Consumer Offset 与 Diff；延迟消息尚未到期时 Diff 为零也不代表 Broker store 可丢弃。

## 2. 恢复到独立的新卷

从 .env.example 建立忽略的 .env.peergrab.local，设置 COMPOSE_PROJECT_NAME=peergrab-local、PEERGRAB_MYSQL_PASSWORD、演示账户口令和端口。旧 .env 保持原样，供回滚使用。新密码可以不同于旧密码；旧 DASH_* 键不会被新配置读取。

~~~bash
cp -n .env.example .env.peergrab.local
chmod 600 .env.peergrab.local
# 编辑 .env.peergrab.local，设置所需密码与端口。
new_compose() {
  docker compose --env-file .env.peergrab.local \
    -f docker-compose.yaml -f docker-compose.full.yaml \
    -p peergrab-local "$@"
}
new_compose config --quiet
new_compose build app worker frontend

new_redis_volume=peergrab-local_peergrab-redis-data
new_broker_volume=peergrab-local_peergrab-rmq-broker-store
if docker volume inspect "$new_redis_volume" >/dev/null 2>&1 ||
   docker volume inspect "$new_broker_volume" >/dev/null 2>&1; then
  echo '新卷已存在，先核对数据，不可覆盖复制' >&2
  exit 1
fi
docker volume create "$new_redis_volume"
docker volume create "$new_broker_volume"
docker run --rm --user 0:0 -v "$old_redis_volume:/src:ro" -v "$new_redis_volume:/dst" \
  --entrypoint sh redis:7.4-alpine -c 'cp -a /src/. /dst/'
docker run --rm --user 0:0 -v "$old_broker_volume:/src:ro" -v "$new_broker_volume:/dst" \
  --entrypoint sh redis:7.4-alpine -c 'cp -a /src/. /dst/ && chown -R 3000:3000 /dst'

new_compose up -d --wait mysql redis rmqnamesrv rmqbroker
new_compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot peer_grab' \
  < "$backup/campus_dash.sql"
new_compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot peer_grab' \
  < migrate-runtime.sql
new_compose run --rm --no-deps mq-init
~~~

新 MySQL 空卷首次启动时会执行 init.sql，随后导入旧库完整转储。migrate-runtime.sql 只在复制后的新库上补齐新列和索引。若源库早于 P6，先在新库执行 migrate-p6.sql，再执行 migrate-runtime.sql。Broker store 中的 Topic、定时器和旧位点随卷复制；mq-init 注册新组。

## 3. 克隆 RocketMQ 位点并验收数据

必须在新 app 和 worker 启动**之前**克隆。RocketMQ 5.3.1 的 `cloneGroupOffset` 会先查询旧组的 `%RETRY%<组名>` Topic；旧组只有新版带业务 Topic 后缀的重试 Topic 时，需先在**新 Broker** 创建空的兼容 Topic。该命令报错时仍可能返回退出码 0，因此必须检查成功文本。任一克隆失败时不要启动消费者。

| 旧消费组 | 新消费组 | Topic |
| --- | --- | --- |
| dash-timeout-consumer | peergrab-timeout-consumer | errand-confirm-timeout |
| dash-autosettle-consumer | peergrab-autosettle-consumer | errand-auto-settle |
| dash-fund-event-consumer | peergrab-fund-event-consumer | errand-fund-event |
| dash-cache-evict-consumer | peergrab-cache-evict-consumer | errand-cache-evict |
| dash-fund-event-push | peergrab-fund-event-push | errand-fund-event |

~~~bash
for mapping in \
  'dash-timeout-consumer peergrab-timeout-consumer errand-confirm-timeout' \
  'dash-autosettle-consumer peergrab-autosettle-consumer errand-auto-settle' \
  'dash-fund-event-consumer peergrab-fund-event-consumer errand-fund-event' \
  'dash-cache-evict-consumer peergrab-cache-evict-consumer errand-cache-evict' \
  'dash-fund-event-push peergrab-fund-event-push errand-fund-event'; do
  read -r source destination topic <<< "$mapping"
  if ! docker exec peergrab-local-rmqbroker-1 sh mqadmin topicList -n rmqnamesrv:9876 |
       grep -Fx "%RETRY%$source" >/dev/null; then
    topic_result=$(docker exec peergrab-local-rmqbroker-1 sh mqadmin updateTopic \
      -n rmqnamesrv:9876 -c DefaultCluster -t "%RETRY%$source" -r 1 -w 1 2>&1)
    [[ "$topic_result" == *success* ]] || { printf '%s\n' "$topic_result" >&2; exit 1; }
  fi
  clone_result=$(docker exec peergrab-local-rmqbroker-1 sh -c \
    'for x in /home/rocketmq/rocketmq-*/bin/mqadmin; do exec sh "$x" cloneGroupOffset -n rmqnamesrv:9876 -s "$1" -d "$2" -t "$3" -o true; done' \
    _ "$source" "$destination" "$topic" 2>&1)
  [[ "$clone_result" == *'clone group offset success'* ]] || { printf '%s\n' "$clone_result" >&2; exit 1; }
  docker exec peergrab-local-rmqbroker-1 sh -c \
    'for x in /home/rocketmq/rocketmq-*/bin/mqadmin; do exec sh "$x" consumerProgress -n rmqnamesrv:9876 -g "$1" -t "$2"; done' \
    _ "$destination" "$topic" > "$backup/$destination.progress.txt"
done

while IFS= read -r table; do
  docker exec peergrab-local-mysql-1 sh -c \
    "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot -N peer_grab -e \"SELECT '$table', COUNT(*) FROM $table\"" \
    >> "$backup/new-counts.tsv"
done < "$backup/tables.txt"
diff -u "$backup/old-counts.tsv" "$backup/new-counts.tsv"
docker exec -i peergrab-local-mysql-1 sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot peer_grab' \
  < ../bench/scripts/verify_fund.sql > "$backup/new-funds.txt"
diff -u "$backup/old-funds.txt" "$backup/new-funds.txt"
docker exec peergrab-local-redis-1 redis-cli DBSIZE > "$backup/new-redis-dbsize.txt"
diff -u "$backup/old-redis-dbsize.txt" "$backup/new-redis-dbsize.txt"
~~~

逐个比较五组新旧 progress.txt：相应队列的 Consumer Offset 必须相同，并检查 Topic、Broker Offset 与 Diff。重启期间定时消息可能到期，所以 Broker Offset 允许增长；不要将其变化误判为丢消息。资金校验每项都必须为 PASS；如果新旧结果相同但都为 FAIL，也必须先处理异常。

## 4. 启动与回滚

验收通过后启动服务，检查健康状态、日志、Topic 积压、资金不变量和只读页面/API。bench/scripts/smoke_e2e.py 会创建任务并改变账户余额，只能在从迁移数据另建的可丢弃演示副本上运行，不能对唯一的新栈运行。

~~~bash
new_compose up -d --wait app worker frontend
new_compose ps
new_compose logs --tail=100 app worker
~~~

如果验收失败，先停止新栈，再重启原容器。旧数据仍在旧卷中。切换后写入新栈的数据不会自动合并回旧栈，回滚前应先停写并保留新卷供排查。

~~~bash
new_compose stop frontend app worker rmqbroker rmqnamesrv redis mysql
docker start "$old_project-mysql-1" "$old_project-redis-1" "$old_project-rmqnamesrv-1"
docker start "$old_project-rmqbroker-1"
docker start "$old_project-worker-1" "$old_project-app-1" "$old_project-frontend-1"
~~~

确认新栈长期稳定后再决定旧容器、旧卷和备份的保留期限。手工创建的新卷初次被 Compose 使用时可能提示缺少 Compose 标签；仅在挂载和数据校验正确时可忽略。
