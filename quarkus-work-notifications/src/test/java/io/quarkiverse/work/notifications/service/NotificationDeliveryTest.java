package io.quarkiverse.work.notifications.service;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static io.restassured.RestAssured.given;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * End-to-end tests verifying that lifecycle events actually deliver HTTP requests
 * to configured webhook URLs. Uses WireMock as an in-process HTTP server.
 */
@QuarkusTest
class NotificationDeliveryTest {

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/hook")).willReturn(ok()));
        wireMock.stubFor(post(urlEqualTo("/slack")).willReturn(ok()));
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void httpWebhook_firesWhenAssigned() throws Exception {
        final String hookUrl = "http://localhost:" + wireMock.port() + "/hook";

        // Create a notification rule for ASSIGNED events
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", hookUrl,
                        "eventTypes", "ASSIGNED"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201);

        // Create and claim a WorkItem (fires ASSIGNED event)
        final String id = given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Delivery test", "category", "test", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("claimantId", "alice"))
                .when().put("/workitems/" + id + "/claim")
                .then().statusCode(200);

        // Give async delivery a moment
        Thread.sleep(500);

        // Verify WireMock received the POST
        wireMock.verify(postRequestedFor(urlEqualTo("/hook"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("X-WorkItem-Event", equalTo("ASSIGNED"))
                .withRequestBody(matching(".*\"eventType\":\"ASSIGNED\".*")));
    }

    @Test
    void httpWebhook_withSecret_includesSignatureHeader() throws Exception {
        final String hookUrl = "http://localhost:" + wireMock.port() + "/hook";

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", hookUrl,
                        "eventTypes", "CREATED",
                        "secret", "my-hmac-secret"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Signed delivery", "category", "test", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201);

        Thread.sleep(500);

        wireMock.verify(postRequestedFor(urlEqualTo("/hook"))
                .withHeader("X-Signature-256", matching("sha256=[0-9a-f]{64}")));
    }

    @Test
    void categoryFilter_onlyFiresForMatchingCategory() throws Exception {
        final String hookUrl = "http://localhost:" + wireMock.port() + "/hook";

        // Rule only for "loan-application" category
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", hookUrl,
                        "eventTypes", "CREATED",
                        "category", "loan-application"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201);

        // Create WorkItem with DIFFERENT category — should NOT trigger
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Legal item", "category", "legal", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201);

        Thread.sleep(300);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/hook")));

        // Create WorkItem with MATCHING category — SHOULD trigger
        given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Loan item", "category", "loan-application", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201);

        Thread.sleep(500);
        wireMock.verify(1, postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    void disabledRule_doesNotFire() throws Exception {
        final String hookUrl = "http://localhost:" + wireMock.port() + "/hook";

        final String ruleId = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", hookUrl,
                        "eventTypes", "CREATED",
                        "enabled", false))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Disabled rule test", "category", "test", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201);

        Thread.sleep(300);
        wireMock.verify(0, postRequestedFor(urlEqualTo("/hook")));
    }

    @Test
    void slackChannel_firesWhenMatched() throws Exception {
        final String slackUrl = "http://localhost:" + wireMock.port() + "/slack";

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "slack",
                        "targetUrl", slackUrl,
                        "eventTypes", "CREATED"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Slack test item", "category", "test", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201);

        Thread.sleep(500);

        wireMock.verify(postRequestedFor(urlEqualTo("/slack"))
                .withRequestBody(matching(".*\"text\".*CREATED.*")));
    }

    @Test
    void failingWebhook_doesNotAffectWorkItemLifecycle() throws Exception {
        // Stub returns 500
        wireMock.stubFor(post(urlEqualTo("/failing")).willReturn(
                com.github.tomakehurst.wiremock.client.WireMock.serverError()));

        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "channelType", "http-webhook",
                        "targetUrl", "http://localhost:" + wireMock.port() + "/failing",
                        "eventTypes", "CREATED"))
                .when().post("/workitem-notification-rules")
                .then().statusCode(201);

        // WorkItem creation must succeed even if webhook returns 500
        final String id = given()
                .contentType(ContentType.JSON)
                .body(Map.of("title", "Resilience test", "category", "test", "createdBy", "test"))
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        // WorkItem must exist and be PENDING
        final String status = given()
                .when().get("/workitems/" + id)
                .then().statusCode(200)
                .extract().path("status");

        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("PENDING");
    }
}
