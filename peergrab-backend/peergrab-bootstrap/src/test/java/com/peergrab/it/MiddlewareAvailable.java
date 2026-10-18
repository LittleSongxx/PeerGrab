package com.peergrab.it;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试依赖 docker-compose 起的常驻 MySQL/Redis。
 *
 * 为什么不用 Testcontainers：compose 已经把容器起好了，测试直连常驻容器可以省掉
 * 每次跑测试都拉起/销毁容器的几十秒开销。代价是需要先 docker compose up，
 * 所以这里做端口探测，中间件没起时整类跳过（skip 而不是 fail），
 * 保证在任何环境下 mvn test 都不会因为没起 Docker 而变红。
 */
final class MiddlewareAvailable {

    private MiddlewareAvailable() {
    }

    /**
     * IT 类只会在 -Dpeergrab.it=true 时执行；此时中间件不可用必须失败而不是静默跳过，
     * 否则"本地全绿"会掩盖集成测试从未真正运行的事实。
     */
    static void require() {
        if (!check()) {
            throw new IllegalStateException("peergrab.it=true 但独立 MySQL/Redis 不可用；"
                    + "请先启动隔离中间件（见 CI 或 bench 运行手册）");
        }
    }

    static boolean check() {
        return portOpen(System.getenv().getOrDefault("PEERGRAB_TEST_DB_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("PEERGRAB_TEST_DB_PORT", "3307")))
                && portOpen(System.getenv().getOrDefault("PEERGRAB_TEST_REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("PEERGRAB_TEST_REDIS_PORT", "6380")));
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 800);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
