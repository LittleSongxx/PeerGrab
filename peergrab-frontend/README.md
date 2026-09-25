# PeerGrab 前端

PeerGrab 的浏览器工作台面向发单人、跑腿者和仲裁员，把任务发布、抢单履约、资金流水与消息放在一个界面中。[项目总览与完整功能实拍](../README.md)在仓库首页。

![PeerGrab 任务广场](../docs/assets/screenshots/square.png)

## 页面与交互

| 页面 | 主要用途 |
| --- | --- |
| 任务广场 | 浏览和筛选任务；游标加载更多 |
| 发布任务 | 填写任务与悬赏，提交后进入资金托管 |
| 我的任务 | 查看自己发布或抢到的任务 |
| 任务详情 | 查看状态、时间线和当前身份可执行操作 |
| 钱包 | 查看余额及逐笔流水 |
| 信用分 | 查看分数、事件与排名 |
| 消息 | 查看持久通知并标记已读 |

桌面使用侧栏工作台，手机视口使用可关闭的导航抽屉。任务、资金和消息操作有加载、错误及空态反馈。浏览器从 API 的 `availableActions` 渲染详情操作，后端维护唯一状态规则。WebSocket 传递实时变化，断线自动重连后可从消息列表补读；长 ID 在 JSON 中按字符串处理。

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

## 代码组织

```text
src/
├── pages/           七个页面：Square / Publish / Mine / Detail / Wallet / Credit / Notifications
├── components/      导航、任务卡片、操作按钮和 SVG 图标
├── api.ts           REST 请求与响应处理
├── ws.ts            WebSocket 连接管理
├── router.tsx       页面路由
├── identity.ts      演示身份显示信息
├── App.tsx          应用结构
└── styles.css       布局、响应式与界面状态
```

技术栈为 React 18、TypeScript、Vite 5 和浏览器 WebSocket API。业务接口保持 `/api/auth`、`/api/errands`、`/api/wallet`、`/api/credit` 与 `/api/notifications`。前端构建验证使用 `npm run build`。

当前演示只支持四个固定身份，任务公开 API 只支持一个名额。广场筛选作用于当前已加载数据；WebSocket 中断后的完整事件需要通过持久通知与页面重新查询补齐。
