# PeerGrab 前端

PeerGrab 的浏览器工作台面向发单人、跑腿者和仲裁员，把任务发布、抢单履约、资金流水与消息放在一个界面中。[项目总览](../README.md)在仓库首页。

## 页面与交互

| 页面 | 主要用途 |
| --- | --- |
| 任务广场 | 浏览和筛选任务；游标加载更多；WS 事件 + 30s 轮询静默刷新 |
| 发布任务 | 填写任务与悬赏（字符串十进制解析，禁止浮点误差），提交后进入资金托管 |
| 我的任务 | 查看自己发布或抢到的任务 |
| 任务详情 | 查看状态、时间线、迟到响应隔离与当前身份可执行操作 |
| 钱包 | 查看余额及逐笔流水（区分用户/平台账户） |
| 信用分 | 查看分数、事件与排名 |
| 消息 | 查看持久通知并标记已读 |

桌面使用侧栏工作台，手机视口使用可关闭的导航抽屉（含焦点陷阱与 Esc/焦点归还）。任务、资金和消息操作有骨架屏、空态、错误与 toast 反馈。浏览器从 API 的 `availableActions` 渲染操作，后端维护唯一状态规则。WebSocket 传递实时变化，降级时顶栏显示"实时已降级"并保持轮询；长 ID 在 JSON 中按字符串处理。

## 本机开发

需要 Node 20+；先按[后端说明](../peergrab-backend/README.md)启动 API 和中间件，再从 `peergrab-frontend` 运行：

```bash
npm ci
npm run dev
```

开发入口为 `http://localhost:5173`。Vite 将 `/api` 和 `/ws` 代理到本机 API `http://localhost:8080`。生产构建与预览：

```bash
npm run build
npm run preview
```

完整 Compose 演示默认访问 `http://127.0.0.1:25173`，前端、API 与 WebSocket 走同源入口。演示身份为发单人 `1001`、跑腿者 `2001`/`2002`、仲裁员 `9001`；各自密码在本地 `../peergrab-backend/docker/.env` 配置，仓库中的 `.env.example` 提供公开的演示默认值。

## 验收与测试

前端有三层验收（详见 [tests/README.md](tests/README.md)），Playwright 版本固定在仓库根 `requirements-dev.txt`：

```bash
# 1) mock UI 契约回归：不需要后端，CI 每次 push 都跑
npm run build
python3 tests/ui_smoke.py --dist dist

# 2) 固定数据截图（不需要后端）
python3 tests/screenshots.py --dist dist

# 3) 真实浏览器 E2E / 真实 API+MQ（需要隔离后端与 Vite dev；只能连可丢弃栈）
python3 tests/live_smoke.py --base http://127.0.0.1:5173 --api-base http://127.0.0.1:8080
python3 tests/live_api_smoke.py --base http://127.0.0.1:8080 --timeout-flow --auto-settle-flow
```

- `tests/mock_api.py`：确定性 mock 数据与静态服务器；
- `tests/ui_smoke.py`：28 个 UI 契约用例（登录/导航/广场/发布/详情/钱包/信用/消息/骨架屏/401 刷新/迟到响应/移动端焦点陷阱/可访问性/console 清洁）；
- `tests/live_smoke.py` / `tests/live_api_smoke.py`：真实链路验收，写入只允许发生在隔离演示栈；
- 报告写入 `verification/<日期>/`，截图写入 `docs/assets/screenshots/`；两者都是本机验收物料，已加入 `.gitignore`，不随仓库提交。

## 设计系统

- 颜色只允许出现在 `src/styles.css` 的 `:root` 令牌定义中；组件规则使用 `var(--token)`，令牌分两层：语义令牌（brand/text/line/success/danger…）优先，自动生成的色阶令牌兜底；
- `src/utils/money.ts` 是金额格式化/解析的唯一入口；`src/utils/toast.ts` + `components/ToastHost.tsx` 统一瞬时反馈；`components/LoadingSkeleton.tsx` 统一骨架屏；
- `src/ws.ts` 对外提供事件订阅与连接可用性订阅；`src/router.tsx` 保持极简 hash 路由。

## 代码组织

```text
src/
├── pages/           七个页面：Square / Publish / Mine / Detail / Wallet / Credit / Notifications
├── components/      导航、任务卡、操作按钮、Toast、骨架屏与 SVG 图标
├── utils/           金额（money）、提示（toast）、幂等键（requestId）
├── api.ts           REST 请求、401 刷新与身份竞态守卫
├── ws.ts            WebSocket 连接、重连退避、降级状态广播
├── router.tsx       极简 hash 路由
├── identity.ts      演示身份显示信息
├── App.tsx          应用结构、登录页与 skip-link
└── styles.css       设计令牌、布局、响应式与界面状态
tests/              mock API、UI 验收、真实链路验收、截图生成
```

技术栈为 React 18、TypeScript、Vite 5 和浏览器 WebSocket API。业务接口保持 `/api/auth`、`/api/errands`、`/api/wallet`、`/api/credit` 与 `/api/notifications`。前端构建验证使用 `npm run build`（TypeScript 开启 `noUnusedLocals`/`noUnusedParameters`，未使用代码会让构建失败）。

当前演示只支持四个固定身份，任务公开 API 只支持一个名额。广场筛选作用于当前已加载数据；WebSocket 中断后的完整事件需要通过持久通知与页面重新查询补齐。
