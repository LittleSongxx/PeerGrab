package com.peergrab.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置一致性门禁：同一逻辑配置只允许一份权威值。
 *
 * <p>API（{@code peergrab-bootstrap}）与 worker（{@code peergrab-worker}）是共享同一套
 * 业务口径的两个进程：结算费率、超时轮次、MQ Topic、缓存分片、信用校准参数等一旦漂移，
 * 两个进程会各自按不同的“真相”执行，且很难在运行期发现。本测试直接解析两份
 * {@code application.yaml}，对交集键做值相等断言，并检查必须共享的键同时存在。
 *
 * <p>白名单只允许真实的进程差异（身份、端口、WS 角色、连接池大小、连接串），
 * 每项都在 {@link #PROCESS_SPECIFIC_KEYS} 处注明原因；新增差异必须显式加白，
 * 不能靠“改测试”悄悄放过。
 *
 * <p>运行：{@code mvn -pl peergrab-bootstrap -Dtest=SharedConfigConsistencyTest test}
 */
class SharedConfigConsistencyTest {

    private static final String BOOTSTRAP_YAML =
            "peergrab-bootstrap/src/main/resources/application.yaml";
    private static final String WORKER_YAML =
            "peergrab-worker/src/main/resources/application.yaml";

    /**
     * 允许两份配置取值不同的键：这些是“进程角色”差异，不是共享业务口径。
     *
     * <p>白名单必须保持最小；任何新增项都要能回答“为什么两个进程必须不同”。
     */
    private static final Set<String> PROCESS_SPECIFIC_KEYS = Set.of(
            // 雪花 ID 的 worker 位：API=1、worker=2，避免两个进程生成重复 ID。
            "peergrab.worker-id",
            // 日志/指标/注册中心里的进程名，必须能区分 API 与 worker。
            "spring.application.name",
            // API 监听 8080、worker 监听 8082；同机部署时不能抢端口。
            "server.port",
            // 只有 API 进程持有 WebSocket；worker=false 以装配 RedisFanoutRealtimeNotifier。
            "peergrab.ws.enabled",
            // readiness 指标随进程角色不同：worker 额外暴露 workerScheduler，API 不装配调度器健康指标。
            "management.endpoint.health.group.readiness.include",
            // 连接池按并发画像定：API 面向在线请求（20/5），worker 跑批（10/2）。
            "spring.datasource.hikari.maximum-pool-size",
            "spring.datasource.hikari.minimum-idle",
            // 连接串参数不同：bootstrap 显式开启 rewriteBatchedStatements=true 支撑批量写入，
            // worker 沿用默认；这是部署参数差异，不改变分账/结算口径，故放行。
            "spring.datasource.url"
    );

    /**
     * 必须同时出现在两份配置中的键。
     *
     * <p>这些值一旦只写在一边，另一个进程就会静默回落到代码内建默认值；
     * 默认值漂移后只能靠事故发现，所以用“键必须存在”提前拦住。
     */
    private static final List<String> MUST_SHARE_KEYS = List.of(
            "peergrab.settle.commission-rate",
            "peergrab.settle.auto-settle-seconds",
            "peergrab.mq.enabled",
            "peergrab.mq.endpoints",
            "peergrab.mq.topic.confirm-timeout",
            "peergrab.mq.topic.auto-settle",
            "peergrab.mq.topic.cache-evict",
            "peergrab.mq.topic.fund-event",
            "peergrab.timeout.confirm-seconds",
            "peergrab.timeout.max-transfer-rounds",
            "peergrab.cache.shards",
            "peergrab.cache.bloom-enabled",
            "peergrab.cache.bloom-refresh-interval-ms",
            "peergrab.credit.calibration-cron",
            "peergrab.credit.calibration-batch-size"
    );

    @Test
    void sharedKeysHaveSingleAuthoritativeValue() throws IOException {
        ConfigPair pair = load();

        assertFalse(pair.intersectionKeys().isEmpty(),
                "两份配置没有任何交集键，请检查 YAML 路径或结构是否已变化");

        List<String> mismatches = new ArrayList<>();
        for (String key : pair.intersectionKeys()) {
            if (PROCESS_SPECIFIC_KEYS.contains(key)) {
                continue;
            }
            String bootstrapValue = pair.bootstrap().get(key);
            String workerValue = pair.worker().get(key);
            if (!bootstrapValue.equals(workerValue)) {
                mismatches.add("- %s: bootstrap=%s (%s) / worker=%s (%s)".formatted(
                        key, bootstrapValue, pair.bootstrapPath(),
                        workerValue, pair.workerPath()));
            }
        }

        assertTrue(mismatches.isEmpty(), () -> """
                同一配置只允许一份权威值，但以下键在 API 与 worker 中不一致：
                %s

                如果确实是进程角色差异，请加入 PROCESS_SPECIFIC_KEYS 并写明原因；否则请对齐取值。
                """.formatted(String.join(System.lineSeparator(), mismatches)));
    }

    @Test
    void keysThatMustBeSharedExistInBothProcesses() throws IOException {
        ConfigPair pair = load();

        List<String> missing = new ArrayList<>();
        for (String key : MUST_SHARE_KEYS) {
            boolean inBootstrap = pair.bootstrap().containsKey(key);
            boolean inWorker = pair.worker().containsKey(key);
            if (!inBootstrap || !inWorker) {
                missing.add("- %s: bootstrap=%s (%s) / worker=%s (%s)".formatted(
                        key,
                        inBootstrap ? pair.bootstrap().get(key) : "<缺失>",
                        pair.bootstrapPath(),
                        inWorker ? pair.worker().get(key) : "<缺失>",
                        pair.workerPath()));
            }
        }

        assertTrue(missing.isEmpty(), () -> """
                以下配置必须同时出现在 API 与 worker，否则缺少的一方会静默回落到代码默认值：
                %s
                """.formatted(String.join(System.lineSeparator(), missing)));
    }

    /**
     * 从 Maven/IDE 的工作目录定位 backend 根：在模块目录里跑就是父目录，在 backend 根跑就是自身。
     */
    private static Path resolveBackendRoot() {
        Path userDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path dirName = userDir.getFileName();
        if (dirName != null && "peergrab-bootstrap".equals(dirName.toString())) {
            return userDir.getParent();
        }
        return userDir;
    }

    private static ConfigPair load() throws IOException {
        Path backendRoot = resolveBackendRoot();
        Path bootstrapPath = backendRoot.resolve(BOOTSTRAP_YAML);
        Path workerPath = backendRoot.resolve(WORKER_YAML);

        assertTrue(Files.isRegularFile(bootstrapPath), () -> "找不到 API 配置: " + bootstrapPath);
        assertTrue(Files.isRegularFile(workerPath), () -> "找不到 worker 配置: " + workerPath);

        return new ConfigPair(flatten(bootstrapPath), flatten(workerPath), bootstrapPath, workerPath);
    }

    private static Map<String, String> flatten(Path yamlPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(yamlPath, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("YAML 根节点必须是映射: " + yamlPath);
            }
            Map<String, String> flattened = new LinkedHashMap<>();
            flattenInto("", root, flattened);
            return flattened;
        }
    }

    private static void flattenInto(String prefix, Map<?, ?> node, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = prefix.isEmpty()
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> child) {
                flattenInto(key, child, target);
            } else if (value instanceof List<?> list) {
                // 当前两份配置没有列表值；保留可读形式，未来出现时仍能参与相等比较。
                target.put(key, list.toString());
            } else {
                target.put(key, value == null ? "null" : String.valueOf(value));
            }
        }
    }

    private record ConfigPair(Map<String, String> bootstrap,
                              Map<String, String> worker,
                              Path bootstrapPath,
                              Path workerPath) {

        private Set<String> intersectionKeys() {
            Set<String> keys = new TreeSet<>(bootstrap.keySet());
            keys.retainAll(worker.keySet());
            return keys;
        }
    }
}
