package io.quarkiverse.workitems.queues.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration and E2E tests for GET /queues/{id}/events SSE stream.
 */
@QuarkusTest
class QueueSSETest {

    @TestHTTPResource("/")
    URI baseUri;

    @Test
    void queueSseEndpoint_returns200_withSseContentType() throws Exception {
        final String queueId = createQueue("SSE test queue", "sse-test/**");
        final HttpResponse<InputStream> response = connectSse("queues/" + queueId + "/events");
        try (InputStream ignored = response.body()) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("content-type").orElse(""))
                    .contains("text/event-stream");
        }
    }

    @Test
    void queueSse_happyPath_addedEventAppearsWhenWorkItemEntersQueue() throws Exception {
        // Filter that routes items with category 'sse-queue-cat' to label 'sse-queue-ev/item'
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"SSE queue filter","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"category == 'sse-queue-cat'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"sse-queue-ev/item"}]}
                        """)
                .post("/filters").then().statusCode(201);

        final String queueId = createQueue("SSE Event Queue", "sse-queue-ev/**");

        final List<String> dataLines = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);

        final Thread sseThread = Thread.ofVirtual().start(() -> {
            try {
                final HttpClient client = HttpClient.newHttpClient();
                final HttpRequest req = HttpRequest.newBuilder()
                        .uri(baseUri.resolve("queues/" + queueId + "/events"))
                        .header("Accept", "text/event-stream").build();
                client.send(req, HttpResponse.BodyHandlers.ofLines())
                        .body()
                        .filter(l -> l.startsWith("data:"))
                        .peek(dataLines::add)
                        .findFirst()
                        .ifPresent(l -> latch.countDown());
            } catch (Exception ignored) {
            }
        });

        Thread.sleep(400);

        given().contentType(ContentType.JSON)
                .body("{\"title\":\"SSE test item\",\"createdBy\":\"test\",\"category\":\"sse-queue-cat\"}")
                .post("/workitems").then().statusCode(201);

        assertThat(latch.await(4, TimeUnit.SECONDS))
                .as("Expected ADDED event in queue SSE stream").isTrue();
        assertThat(dataLines.get(0)).contains("ADDED");

        sseThread.interrupt();
    }

    private String createQueue(final String name, final String pattern) {
        return given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + name + "\",\"labelPattern\":\"" + pattern + "\",\"scope\":\"ORG\"}")
                .post("/queues").then().statusCode(201).extract().path("id");
    }

    private HttpResponse<InputStream> connectSse(final String path) throws Exception {
        final HttpClient client = HttpClient.newHttpClient();
        return client.send(
                HttpRequest.newBuilder().uri(baseUri.resolve(path))
                        .header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }
}
