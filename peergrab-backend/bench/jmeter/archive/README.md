# JMeter 失效诊断计划归档

[`s2-open-model-invalid.jmx`](s2-open-model-invalid.jmx) 是 2026-09-28 ECS 迁移期间用于发现发压模型失配的**无效诊断计划**，不用于后续容量报告。它在 Open Model 下为每次请求创建新连接，且到达时间表结束时中断在途请求；该轮 100 目标 RPS 的 4,000 条样本有 33 次非 HTTP 失败，不能据此说应用在 100 RPS 失稳。[有效 S2 计划](../s2/cursor-first-pooled.jmx)和[原始结论](../../reports/peergrab-current/report-ecs-jmeter-20260928.md)另存。
