package com.example.campusmarket.supportai.infrastructure;

import com.example.campusmarket.supportai.application.AnswerService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证交易状态客户端只把真实 404 归类为资源不存在。 */
class HttpTradeStatusReaderTest {
    private static final UUID ORDER_ID = UUID.fromString(
        "11111111-1111-1111-1111-111111111111");
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void downstreamUnauthorizedIsDependencyUnavailableNotNotFound() throws IOException {
        HttpTradeStatusReader reader = reader(401, "unauthorized");

        assertThatThrownBy(() -> reader.read("orders", ORDER_ID, "internal-token"))
            .isInstanceOf(AnswerService.DependencyUnavailableException.class)
            .isNotInstanceOf(AnswerService.ResourceNotFoundException.class);
    }

    @Test
    void downstreamForbiddenIsDependencyUnavailableNotNotFound() throws IOException {
        HttpTradeStatusReader reader = reader(403, "forbidden");

        assertThatThrownBy(() -> reader.read("orders", ORDER_ID, "internal-token"))
            .isInstanceOf(AnswerService.DependencyUnavailableException.class)
            .isNotInstanceOf(AnswerService.ResourceNotFoundException.class);
    }

    @Test
    void emptySuccessfulBodyIsDependencyUnavailableNotNotFound() throws IOException {
        HttpTradeStatusReader reader = reader(200, "");

        assertThatThrownBy(() -> reader.read("orders", ORDER_ID, "internal-token"))
            .isInstanceOf(AnswerService.DependencyUnavailableException.class)
            .isNotInstanceOf(AnswerService.ResourceNotFoundException.class);
    }

    @Test
    void actualResourceNotFoundRemainsBusinessNotFound() throws IOException {
        HttpTradeStatusReader reader = reader(404, "not found");

        assertThatThrownBy(() -> reader.read("orders", ORDER_ID, "internal-token"))
            .isInstanceOf(AnswerService.ResourceNotFoundException.class);
    }

    private HttpTradeStatusReader reader(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> write(exchange, status, body));
        server.start();
        return new HttpTradeStatusReader(RestClient.builder().build(),
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange,
                              int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
