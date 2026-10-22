# PeerGrab 文档索引

这里是 PeerGrab 全部工程文档的唯一入口。**新增/移动文档时必须同步更新本页**，CI 的
`scripts/check_docs.py` 会检查“docs 下每篇 Markdown 都被本索引链接”以及相对链接可达。

> 教程、架构图、功能截图、本地验收证据属于本机个人物料，默认不纳入仓库
> （见 `.gitignore`：`docs/tutorial/`、`docs/assets/screenshots/`、`peergrab-frontend/verification/`）。

## 1. 按目的找文档

| 你想了解 | 先读 |
| --- | --- |
| 项目是什么、怎么跑、实测边界 | [根 README](../README.md) |
| 当前架构与关键取舍（简明版） | [架构设计与技术选型](architecture/架构设计与技术选型.md) |
| 完整设计基准：四条链路、方案演进矩阵、指标口径、微服务演进 | [design-benchmark](architecture/design-benchmark.md) |
| 所有配置项与生效进程 | [config-reference](architecture/config-reference.md) |
| 本地消息表/outbox 与 RocketMQ 事务消息的取舍 | [messaging-consistency](architecture/messaging-consistency.md) |
| 钱包账户拓扑、每单托管户与迁移 | [wallet-account-topology](architecture/wallet-account-topology.md) |
| 为什么按 campus_id 分片、为什么运行链路还没接线 | [sharding-design](architecture/sharding-design.md) |
| 不变式清单与拆层对照实验 | [invariants-and-control-experiments](architecture/invariants-and-control-experiments.md) |
| 工程分期、关键取舍与踩坑档案 | [design-evolution](engineering/design-evolution.md) |
| 压测方法论、场景设计、报告口径 | [benchmark/methodology](benchmark/methodology.md) |
| 压测运行手册与历史报告 | [bench/README](../peergrab-backend/bench/README.md) |
| 代码审计与历史问题清单 | [PeerGrab-全面深度分析报告](analysis/PeerGrab-全面深度分析报告.md) |
| 前端验收脚本与复跑命令 | [前端测试说明](../peergrab-frontend/tests/README.md) |

## 2. 单一事实来源（Single Source of Truth）

同一事实只允许有一个权威来源；其它文档引用它，而不是复制数值。

| 事实 | 权威来源 | 校验方式 |
| --- | --- | --- |
| 后端版本 | `peergrab-backend/pom.xml`（`1.0.0-SNAPSHOT`，Maven 聚合根） | `mvn -B -ntp help:evaluate -Dexpression=project.version -q -DforceStdout` |
| 前端版本 | `peergrab-frontend/package.json`（`1.0.0`） | `npm run build` 读同一文件 |
| 当前测试数量（单测 + IT） | 同一条命令 `mvn -B -ntp -Dpeergrab.it=true clean test` + [summarize_tests.py](../peergrab-backend/tools/summarize_tests.py) | CI backend job 写入 step summary；`--strict --require-it` 门禁 |
| 历史测试数字（214/223/298/308…） | 根 README「上线与实测」历史段落 | 标注日期与版本，不与当前口径混用 |
| 配置键与默认值 | [config-reference](architecture/config-reference.md) + 两份 `application.yaml` | `SharedConfigConsistencyTest` 校验共享键一致 |
| 压测数字 | `peergrab-backend/bench/reports/peergrab-current/` + [ASSET_CATALOG](../peergrab-backend/bench/ASSET_CATALOG.md) | 报告必须带镜像/工具/路径/窗口/错误分母 |
| 模块清单与运行包边界 | [design-benchmark](architecture/design-benchmark.md) §2 + 各模块 `pom.xml` | `mvn -B -ntp -pl ... test` 构建即校验 |
| API 端点 | `peergrab-backend/peergrab-presentation/` 控制器 | 契约测试 / live API 脚本 |

## 3. 文档地图（目录职责）

```text
docs/
├── README.md                          # 本索引
├── analysis/                          # 只读审计（历史结论，不做运行时承诺）
├── architecture/                      # 架构基准、配置、消息一致性、分片、不变式
├── engineering/                       # 演进史、取舍与踩坑档案
└── benchmark/                         # 压测方法论与口径
```

## 4. 维护规则

1. **只写可核对的当前事实**：计划中的工具/能力不要写成已落地；历史数字必须标注版本与日期。
2. **数字必须带口径**：性能与测试数字注明镜像/提交、工具、负载窗口、错误分母与统计范围。
3. **新增文档先登记**：在本文第 1 节表格加一行相对链接，否则 CI 的 `check_docs.py` 会失败。
4. **生成物与本机物料不进仓库**：教程、架构图、功能截图、验收证据只保留在本地并已加入 `.gitignore`，不要提交。
