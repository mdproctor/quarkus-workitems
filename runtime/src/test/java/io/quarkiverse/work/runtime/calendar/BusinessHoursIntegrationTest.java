package io.quarkiverse.work.runtime.calendar;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for business hours deadline resolution via the REST API.
 *
 * <p>
 * The test application.properties configures Mon-Fri 09:00-17:00 UTC.
 * These tests verify that business hours fields are resolved correctly
 * at WorkItem creation time.
 */
@QuarkusTest
class BusinessHoursIntegrationTest {

    @Test
    void createWithExpiresAtBusinessHours_setsAbsoluteExpiresAt() {
        final var body = Map.of(
                "title", "BH expiry test",
                "category", "test",
                "createdBy", "test",
                "expiresAtBusinessHours", 8);

        final String id = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        final String expiresAtStr = given()
                .when().get("/workitems/" + id)
                .then().statusCode(200)
                .extract().path("expiresAt");

        assertThat(expiresAtStr).isNotNull();
        // expiresAt must be in the future and not more than 8 business days away
        final Instant expiresAt = Instant.parse(expiresAtStr);
        assertThat(expiresAt).isAfter(Instant.now());
        // 8 business hours ≤ 3 calendar days (reasonable upper bound for any start time)
        assertThat(expiresAt).isBefore(Instant.now().plus(3, ChronoUnit.DAYS));
    }

    @Test
    void createWithClaimDeadlineBusinessHours_setsAbsoluteClaimDeadline() {
        final var body = Map.of(
                "title", "BH claim test",
                "category", "test",
                "createdBy", "test",
                "claimDeadlineBusinessHours", 2);

        final String id = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        final String claimDeadlineStr = given()
                .when().get("/workitems/" + id)
                .then().statusCode(200)
                .extract().path("claimDeadline");

        assertThat(claimDeadlineStr).isNotNull();
        final Instant claimDeadline = Instant.parse(claimDeadlineStr);
        assertThat(claimDeadline).isAfter(Instant.now());
        // 2 business hours ≤ 1 calendar day
        assertThat(claimDeadline).isBefore(Instant.now().plus(1, ChronoUnit.DAYS));
    }

    @Test
    void absoluteExpiresAt_takesPrecedenceOverBusinessHours() {
        final Instant absolute = Instant.now().plus(72, ChronoUnit.HOURS);
        final var body = Map.of(
                "title", "Precedence test",
                "category", "test",
                "createdBy", "test",
                "expiresAt", absolute.toString(),
                "expiresAtBusinessHours", 1);

        final String id = given()
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        final String expiresAtStr = given()
                .when().get("/workitems/" + id)
                .then().statusCode(200)
                .extract().path("expiresAt");

        // absolute takes precedence — should be ≈ 72h away (within a few seconds)
        final Instant result = Instant.parse(expiresAtStr);
        assertThat(result).isCloseTo(absolute, org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
    }
}
