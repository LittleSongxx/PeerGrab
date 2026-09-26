package com.peergrab.bench;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Verifies live Docker resources and the actual target URL before benchmark traffic. */
final class BenchSafety {
    private static final Pattern PROJECT = Pattern.compile("peergrab-bench-[a-z0-9][a-z0-9_-]*");
    private static final Pattern CONTAINER_ID = Pattern.compile("[a-f0-9]{64}");
    private BenchSafety() {}

    static void requireDisposableStack() {
        requireDisposableStack(System.getenv("PEERGRAB_BENCH_BASE_URL"));
    }

    static void requireDisposableStack(String baseUrl) {
        runPreflight(baseUrl, null, null, null);
    }

    static void requireBenchmarkMqMode(boolean enabled) {
        runPreflight(System.getenv("PEERGRAB_BENCH_BASE_URL"), enabled, null, null);
    }

    static void requireTimeoutScanMode(boolean enabled) {
        runPreflight(System.getenv("PEERGRAB_BENCH_BASE_URL"), null, enabled, null);
    }

    static void requireConfirmSeconds(long seconds) {
        runPreflight(System.getenv("PEERGRAB_BENCH_BASE_URL"), null, null, seconds);
    }

    private static void runPreflight(String baseUrl, Boolean requiredMqEnabled,
                                     Boolean requiredTimeoutScanEnabled, Long requiredConfirmSeconds) {
        if ("container".equals(System.getenv("PEERGRAB_BENCH_RUNNER_CONTEXT"))) {
            requireContainerRunner(baseUrl, requiredMqEnabled, requiredTimeoutScanEnabled,
                    requiredConfirmSeconds);
            return;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Set PEERGRAB_BENCH_BASE_URL or pass the benchmark URL");
        }
        Path script = findPreflight();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder("python3", script.toString(), "--base-url", baseUrl);
            if (requiredMqEnabled != null) {
                builder.command().add("--require-mq-enabled");
                builder.command().add(requiredMqEnabled.toString());
            }
            if (requiredTimeoutScanEnabled != null) {
                builder.command().add("--require-timeout-scan-enabled");
                builder.command().add(requiredTimeoutScanEnabled.toString());
            }
            if (requiredConfirmSeconds != null) {
                builder.command().add("--require-confirm-seconds");
                builder.command().add(requiredConfirmSeconds.toString());
            }
            process = builder.redirectErrorStream(true).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Benchmark preflight timed out");
            }
            String output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                throw new IllegalStateException(output.trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to run benchmark preflight", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Benchmark preflight interrupted", e);
        }
    }

    static void requireBenchmarkMqEndpoint(String endpoint) {
        requireDisposableStack();
        if ("container".equals(System.getenv("PEERGRAB_BENCH_RUNNER_CONTEXT"))) {
            if (!"rmqbroker:8081".equals(endpoint)) {
                throw new IllegalStateException("Container MQ endpoint must be rmqbroker:8081");
            }
            return;
        }
        String port = System.getenv("PEERGRAB_TEST_MQ_PORT");
        if (port == null || !port.matches("[1-9][0-9]{0,4}")
                || Integer.parseInt(port) > 65535
                || !("127.0.0.1:" + port).equals(endpoint)) {
            throw new IllegalStateException("MQ endpoint must equal 127.0.0.1:PEERGRAB_TEST_MQ_PORT "
                    + "verified by benchmark preflight");
        }
    }

    private static void requireContainerRunner(String baseUrl, Boolean requiredMqEnabled,
                                               Boolean requiredTimeoutScanEnabled,
                                               Long requiredConfirmSeconds) {
        String project = System.getenv("PEERGRAB_BENCH_PROJECT");
        String id = System.getenv("PEERGRAB_BENCH_RUNNER_ID");
        String ip = System.getenv("PEERGRAB_BENCH_RUNNER_IP");
        if (!"YES".equals(System.getenv("PEERGRAB_BENCH_DISPOSABLE"))
                || project == null || !PROJECT.matcher(project).matches()
                || !project.equals(System.getenv("COMPOSE_PROJECT_NAME"))
                || !"http://app:8080".equals(baseUrl)
                || !"mysql".equals(System.getenv("PEERGRAB_TEST_DB_HOST"))
                || !"3306".equals(System.getenv("PEERGRAB_TEST_DB_PORT"))
                || !"8081".equals(System.getenv("PEERGRAB_TEST_MQ_PORT"))
                || id == null || !CONTAINER_ID.matcher(id).matches()
                || ip == null || ip.isBlank()) {
            throw new IllegalStateException("Benchmark container identity or internal target mismatch");
        }
        try {
            if (!InetAddress.getLocalHost().getHostName().equals(id.substring(0, 12))) {
                throw new IllegalStateException("Runner hostname does not match Docker inspected container ID");
            }
            boolean ownsInspectedIp = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(iface -> Collections.list(iface.getInetAddresses()).stream())
                    .anyMatch(address -> address.getHostAddress().equals(ip));
            if (!ownsInspectedIp) {
                throw new IllegalStateException("Runner IP does not match Docker inspected benchmark network");
            }
            String password = System.getenv("PEERGRAB_TEST_DB_PASSWORD");
            if (password == null || password.isBlank()) {
                throw new IllegalStateException("Missing benchmark database credential");
            }
            try (var db = DriverManager.getConnection(
                    "jdbc:mysql://mysql:3306/peer_grab?useSSL=false&allowPublicKeyRetrieval=true", "root", password);
                 var statement = db.prepareStatement(
                         "SELECT project_name FROM bench_guard WHERE guard_key = 'project'");
                 var rows = statement.executeQuery()) {
                if (!rows.next() || !project.equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("Database benchmark marker differs from runner project");
                }
            }
            var connection = new URL(baseUrl + "/api/health").openConnection();
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            try (var body = connection.getInputStream()) {
                String health = new String(body.readAllBytes());
                if (!health.contains("\"status\":\"UP\"")) {
                    throw new IllegalStateException("Benchmark application health is not UP");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Container benchmark preflight failed: " + e.getMessage(), e);
        }
        if (requiredMqEnabled != null
                && !requiredMqEnabled.toString().equals(System.getenv("PEERGRAB_BENCH_VERIFIED_MQ_MODE"))) {
            throw new IllegalStateException("MQ mode does not match host-inspected benchmark worker");
        }
        if (requiredTimeoutScanEnabled != null
                && !requiredTimeoutScanEnabled.toString().equals(
                    System.getenv("PEERGRAB_BENCH_VERIFIED_SCAN_MODE"))) {
            throw new IllegalStateException("Timeout scan mode does not match host-inspected benchmark worker");
        }
        if (requiredConfirmSeconds != null
                && !requiredConfirmSeconds.toString().equals(
                    System.getenv("PEERGRAB_BENCH_VERIFIED_CONFIRM_SECONDS"))) {
            throw new IllegalStateException("Confirmation timeout does not match host-inspected benchmark worker");
        }
    }

    private static Path findPreflight() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (Path candidate : new Path[] {
                    dir.resolve("peergrab-backend/bench/scripts/preflight.py"),
                    dir.resolve("bench/scripts/preflight.py")}) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Run benchmark client from the PeerGrab checkout with preflight.py");
    }
}
