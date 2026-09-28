package com.peergrab.bench;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CacheLoadClientTest {
    @Test
    void readComparisonDefaultsAndBoundedDurationAreExplicit() {
        var defaults = CacheLoadClient.parse(new String[]{"b", "http://127.0.0.1:8080"});
        assertEquals(50, defaults.concurrency());
        assertEquals(100, defaults.iterations());
        assertEquals(1, defaults.trials());
        assertEquals(CacheLoadClient.DEFAULT_SEED, defaults.seed());

        var repeated = CacheLoadClient.parse(new String[]{"a", "http://127.0.0.1:8080",
                "--concurrency=20", "--iterations=300", "--trials=3", "--seed=17"});
        assertEquals(20, repeated.concurrency());
        assertEquals(300, repeated.iterations());
        assertEquals(3, repeated.trials());
        assertEquals(17, repeated.seed());

        var timed = CacheLoadClient.parse(new String[]{"b", "--duration-seconds=60", "--trials=2"});
        assertEquals(60, timed.durationSeconds());
        assertEquals(Integer.MAX_VALUE, timed.iterations());
        assertThrows(IllegalArgumentException.class, () -> CacheLoadClient.parse(
                new String[]{"c", "--duration-seconds=60"}));
        assertThrows(IllegalArgumentException.class, () -> CacheLoadClient.parse(
                new String[]{"c", "--concurrency=100", "--iterations=100"}));
    }

    @Test
    void mixedModeReadsEveryNewlyPublishedTaskAndProbeSeesCancellation() throws Exception {
        AtomicLong newest = new AtomicLong();
        AtomicInteger nextId = new AtomicInteger(500);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        List<Long> readIds = Collections.synchronizedList(new ArrayList<>());
        AtomicLong cancelled = new AtomicLong();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/errands", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String response;
            if ("/api/errands".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                long id = nextId.incrementAndGet();
                newest.set(id);
                writes.incrementAndGet();
                response = "{\"code\":\"OK\",\"data\":{\"errandId\":\"" + id + "\"}}";
            } else if (path.endsWith("/cancel")) {
                cancelled.set(Long.parseLong(path.split("/")[3]));
                response = "{\"code\":\"OK\",\"data\":{\"result\":\"REFUNDED\"}}";
            } else {
                long id = Long.parseLong(path.split("/")[3]);
                readIds.add(id);
                reads.incrementAndGet();
                String status = id == cancelled.get() ? "CANCELLED" : "PUBLISHED";
                response = "{\"code\":\"OK\",\"data\":{\"status\":\"" + status + "\"}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            String baseUrl = "http://" + server.getAddress().getHostString()
                    + ":" + server.getAddress().getPort();
            var config = CacheLoadClient.parse(new String[]{"c", baseUrl,
                    "--concurrency=1", "--iterations=20"});
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger fail = new AtomicInteger();
            AtomicLong writeCount = new AtomicLong();
            AtomicLong firstId = new AtomicLong();
            List<Long> published = new ArrayList<>();
            List<Long> latencies = new ArrayList<>();
            CacheLoadClient.runRound(config, HttpClient.newHttpClient(), "test", 0, 1,
                    "trial", Long.MAX_VALUE, ok, fail, writeCount, firstId, published, latencies);
            assertEquals(20, ok.get());
            assertEquals(0, fail.get());
            assertEquals(2, writeCount.get());
            assertEquals(2, writes.get());
            assertEquals(18, reads.get());
            assertEquals(20, latencies.size());
            assertEquals(List.of(501L, 502L), published);
            assertEquals(Collections.nCopies(9, 501L), readIds.subList(0, 9));
            assertEquals(Collections.nCopies(9, 502L), readIds.subList(9, 18));
            assertEquals(501L, firstId.get());

            assertTrue(CacheLoadClient.readStatus(HttpClient.newHttpClient(), baseUrl,
                    "test", firstId.get(), "PUBLISHED"));
            assertTrue(CacheLoadClient.cancel(HttpClient.newHttpClient(), baseUrl,
                    "test", firstId.get()));
            assertTrue(CacheLoadClient.readStatus(HttpClient.newHttpClient(), baseUrl,
                    "test", firstId.get(), "CANCELLED"));
        } finally {
            server.stop(0);
        }
    }
}
