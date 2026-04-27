package io.quarkiverse.work.notifications.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Integration tests for the notification rule REST API.
 */
@QuarkusTest
class NotificationRuleResourceTest {

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_happyPath_returns201() {
        final Response resp = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", "https://example.com/hook",
                        "eventTypes", "ASSIGNED,COMPLETED"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201)
                .extract().response();

        assertThat(resp.jsonPath().getString("id")).isNotNull();
        assertThat(resp.jsonPath().getString("channelType")).isEqualTo("http-webhook");
        assertThat(resp.jsonPath().getString("targetUrl")).isEqualTo("https://example.com/hook");
        assertThat(resp.jsonPath().getBoolean("enabled")).isTrue();
        // secret must NOT appear in response
        assertThat(resp.asString()).doesNotContain("secret");
    }

    @Test
    void create_withCategoryFilter_returns201() {
        final String id = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "slack",
                        "targetUrl", "https://hooks.slack.com/test",
                        "eventTypes", "ASSIGNED",
                        "category", "loan-application"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201)
                .extract().path("id");

        final String category = given()
                .when().get("/workitem-notification-rules/" + id)
                .then().statusCode(200)
                .extract().path("category");

        assertThat(category).isEqualTo("loan-application");
    }

    @Test
    void create_missingChannelType_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "targetUrl", "https://example.com/hook",
                        "eventTypes", "ASSIGNED"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(400);
    }

    @Test
    void create_missingTargetUrl_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "slack",
                        "eventTypes", "ASSIGNED"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(400);
    }

    @Test
    void create_missingEventTypes_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", "https://example.com/hook"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(400);
    }

    // ── read ──────────────────────────────────────────────────────────────────

    @Test
    void getById_returns200() {
        final String id = createRule("http-webhook", "CREATED");
        given()
                .when().get("/workitem-notification-rules/" + id)
                .then().statusCode(200);
    }

    @Test
    void getById_nonExistent_returns404() {
        given()
                .when().get("/workitem-notification-rules/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    @Test
    void list_returnsEnabledRules() {
        // Create two rules — one enabled, one disabled
        final String id1 = createRule("slack", "ASSIGNED");
        final String id2 = createRule("teams", "COMPLETED");
        // Disable id2
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("enabled", false))
                .when().put("/workitem-notification-rules/" + id2)
                .then().statusCode(200);

        final List<Map<String, Object>> rules = given()
                .when().get("/workitem-notification-rules")
                .then().statusCode(200)
                .extract().jsonPath().getList("$");

        final var ids = rules.stream().map(r -> (String) r.get("id")).toList();
        assertThat(ids).contains(id1);
        assertThat(ids).doesNotContain(id2); // disabled rules excluded from default list
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void update_disableRule_returns200() {
        final String id = createRule("slack", "ASSIGNED");

        final Boolean enabled = given()
                .contentType(ContentType.JSON)
                .body(Map.of("enabled", false))
                .when().put("/workitem-notification-rules/" + id)
                .then().statusCode(200)
                .extract().path("enabled");

        assertThat(enabled).isFalse();
    }

    @Test
    void update_changeTargetUrl_returns200() {
        final String id = createRule("http-webhook", "CREATED");

        final String url = given()
                .contentType(ContentType.JSON)
                .body(Map.of("targetUrl", "https://new-endpoint.example.com/hook"))
                .when().put("/workitem-notification-rules/" + id)
                .then().statusCode(200)
                .extract().path("targetUrl");

        assertThat(url).isEqualTo("https://new-endpoint.example.com/hook");
    }

    @Test
    void update_nonExistent_returns404() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("enabled", false))
                .when().put("/workitem-notification-rules/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_returns204() {
        final String id = createRule("teams", "EXPIRED");
        given().when().delete("/workitem-notification-rules/" + id)
                .then().statusCode(204);
        given().when().get("/workitem-notification-rules/" + id)
                .then().statusCode(404);
    }

    @Test
    void delete_nonExistent_returns404() {
        given().when().delete("/workitem-notification-rules/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String createRule(final String channelType, final String eventTypes) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", channelType,
                        "targetUrl", "https://example.com/hook-" + UUID.randomUUID(),
                        "eventTypes", eventTypes))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201)
                .extract().path("id");
    }
}
