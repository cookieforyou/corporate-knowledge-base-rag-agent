package com.enterprise.kb.loadtest.stub;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成桩契约自测（纯 JDK，随机端口，秒级）：守护 OpenAI 兼容形态
 * （chunk 序列 / finish_reason / usage / [DONE]），防桩契约漂移污染场景 B/D 基线。
 */
class StubChatServerTest {

    @Test
    void streamsOpenAiCompatibleChunksThenDone() throws Exception {
        HttpServer server = StubChatServer.start(0, 5, 1, 0);
        try {
            HttpResponse<String> response = post(server,
                "{\"model\":\"stub-model\",\"stream\":true,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"连通性自测\"}]}");
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/event-stream"));
            String body = response.body();
            assertTrue(body.contains("chat.completion.chunk"), "应产出 chunk 序列");
            assertTrue(body.contains("\"finish_reason\":\"stop\""), "应含终止 chunk");
            assertTrue(body.contains("\"completion_tokens\":5"), "usage 计数应与 tokens 一致");
            assertTrue(body.trim().endsWith("data: [DONE]"), "应以 [DONE] 收束");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nonStreamRequestReturnsSingleCompletion() throws Exception {
        HttpServer server = StubChatServer.start(0, 3, 1, 0);
        try {
            HttpResponse<String> response = post(server,
                "{\"model\":\"stub-model\",\"stream\":false,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"非流式自测\"}]}");
            assertEquals(200, response.statusCode());
            String body = response.body();
            assertTrue(body.contains("\"object\":\"chat.completion\""));
            assertTrue(body.contains("\"message\":{\"role\":\"assistant\""));
            assertTrue(body.contains("\"finish_reason\":\"stop\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void healthEndpointResponds() throws Exception {
        HttpServer server = StubChatServer.start(0, 1, 1, 0);
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + server.getAddress().getPort() + "/health"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals("OK", response.body());
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponse<String> post(HttpServer server, String body) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + server.getAddress().getPort() + "/chat/completions"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
