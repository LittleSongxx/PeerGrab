# PeerGrab 前端验收脚本

前端验收分三层，全部使用固定版本 Playwright（见仓库根 `requirements-dev.txt`）：

| 套件 | 依赖 | 作用 | 入口 |
| --- | --- | --- | --- |
| mock UI 验收 | 只需 `dist/` 构建产物 | 28 个用例：登录/导航/广场/发布/详情/钱包/信用/消息/骨架屏/401 刷新/迟到响应/移动端焦点陷阱/可访问性/console 清洁 | `python3 tests/ui_smoke.py --dist dist` |
| 真实浏览器 E2E | 运行中的后端 + Vite dev | 4 个演示身份走完发布→抢单→履约→结算、取消退款、争议仲裁，写入全部由 UI 点击产生 | `python3 tests/live_smoke.py --base http://127.0.0.1:5173 --api-base http://127.0.0.1:8080` |
| 真实 API/MQ 验收 | 运行中的后端 + 短超时配置 | 并发抢单恰好 1 人成功、发布/结算/退款/仲裁幂等、越权拒绝、超时递补、自动结算；可选 worker 日志作 MQ 证据 | `python3 tests/live_api_smoke.py --base http://127.0.0.1:8080 --timeout-flow --auto-settle-flow` |
| 截图生成 | 只需 `dist/` 构建产物 | 用固定 mock 数据生成 7 张桌面截图、7 张 390px 移动截图与总览图 | `python3 tests/screenshots.py --dist dist` |

## 真实栈复跑

本仓库提供 `peergrab-backend/docker/run-live-acceptance.sh`：用 compose 起中间件，并把 API/Worker
放进同一 compose 网络（与生产部署形态一致，RocketMQ 5.x 路由才可达），再用 Vite dev + 下面脚本验收。
复跑证据默认写入 `verification/<日期>/`（本地物料，不纳入仓库）。

## 安装

```bash
cd peergrab-frontend
npm ci && npm run build
python3 -m pip install --user -r ../requirements-dev.txt
python3 -m playwright install chromium
```

## mock 数据与约定

- `tests/mock_api.py` 提供确定性 API 夹具（固定任务/账户/时间）与静态服务器；
- `tests/ui_smoke.py` 不依赖后端、数据库、MQ，可直接进 CI；
- 证据报告默认写入 `verification/<日期>/`（JSON，本机保留），截图写入 `docs/assets/screenshots/`（本机保留）；
- 真实链路脚本会创建演示任务并改变演示账户余额，**只能连可丢弃的隔离栈**，禁止指向演示库或生产库。

## 与真实后端的边界

- mock 套件验证前端契约与交互，不替代后端并发/资金正确性测试（见后端 `-Dpeergrab.it=true`）；
- 真实浏览器 E2E 的写入全部通过 UI 点击完成，API 仅做只读核对；
- 真实 API/MQ 需要以短确认超时和长扫描间隔启动 API/Worker，才能验证"MQ 主通道"语义（见 `verification/` 下 README 的复跑命令）。
