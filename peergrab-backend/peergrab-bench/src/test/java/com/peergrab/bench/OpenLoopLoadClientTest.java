package com.peergrab.bench;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class OpenLoopLoadClientTest {

    @Test
    void rejectsRatesThatWouldAllocateUnboundedSamples() {
        assertThrows(IllegalArgumentException.class, () -> OpenLoopLoadClient.parseArguments(
                new String[]{"http://127.0.0.1:8080", "50000", "60", "60", "100"}));
        assertThrows(IllegalArgumentException.class, () -> OpenLoopLoadClient.parseArguments(
                new String[]{"http://127.0.0.1:8080", "10,10", "1", "1", "100"}));
        assertThrows(IllegalArgumentException.class, () -> OpenLoopLoadClient.parseArguments(
                new String[]{"http://127.0.0.1:8080", "10", "1", "1", "2001"}));
    }

    @Test
    void offersContinueWhileSlowRequestsAreStillInFlight() throws Exception {
        ExecutorService serverThreads = Executors.newFixedThreadPool(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverThreads);
        server.createContext("/api/errands", exchange -> {
            try {
                Thread.sleep(300);
                byte[] response = "{\"code\":\"OK\",\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/api/errands?campusId=1"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            OpenLoopLoadClient.PhaseResult result =
                    OpenLoopLoadClient.runPhase(client, request, 20, 1, 1, 2_000, true);

            assertEquals(20, result.offered(), "the offered schedule must not wait for responses");
            assertEquals(result.sent(), result.completed());
            assertTrue(result.localRejected() >= 10, "capacity must reject instead of accumulating work");
            assertTrue(result.peakInFlight() <= 1);
            assertEquals(result.completed(), result.sortedLatencyMicros().length);
            assertTrue(result.percentile(99) >= 250_000);
        } finally {
            server.stop(0);
            serverThreads.shutdownNow();
        }
    }
}
