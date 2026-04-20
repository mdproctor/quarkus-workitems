package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

import java.util.Map;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Tests for atomic claim via JPA @Version optimistic locking (#96).
 *
 * Each REST request creates its own JPA session and loads fresh from the DB,
 * so a real OptimisticLockException requires two sessions holding the SAME
 * stale version simultaneously — that is Hibernate's contract, not application
 * logic to test. We verify our wrapper: @Version field is mapped, the mapper
 * returns 409, and the happy path still works.
 */
@QuarkusTest
class WorkItemOptimisticLockTest {

    // Unit: exception mapper

    @Test
    void exceptionMapper_returns409_withConflictAndRetryHint() {
        final OptimisticLockExceptionMapper mapper = new OptimisticLockExceptionMapper();
        final Response response = mapper.toResponse(new OptimisticLockException("version mismatch"));
        assertThat(response.getStatus()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) response.getEntity();
        assertThat(body.get("error").toString()).containsIgnoringCase("conflict");
        assertThat(body.get("hint").toString()).containsIgnoringCase("retry");
    }

    @Test
    void exceptionMapper_hint_mentionsGet() {
        final OptimisticLockExceptionMapper mapper = new OptimisticLockExceptionMapper();
        @SuppressWarnings("unchecked")
        final Map<String, Object> body = (Map<String, Object>) mapper.toResponse(new OptimisticLockException()).getEntity();
        assertThat(body.get("hint").toString()).containsIgnoringCase("GET");
    }

    // Integration: version field in response

    @Test
    void workItem_response_includesVersionField() {
        given().get("/workitems/" + createWorkItem())
                .then().statusCode(200).body("version", notNullValue());
    }

    @Test
    void workItem_freshCreate_hasVersionZero() {
        final int version = given().get("/workitems/" + createWorkItem())
                .then().statusCode(200).extract().path("version");
        assertThat(version).isEqualTo(0);
    }

    @Test
    void workItem_afterClaim_versionIncremented() {
        final String itemId = createWorkItem();
        given().queryParam("claimant", "alice").put("/workitems/" + itemId + "/claim").then().statusCode(200);
        assertThat(version(itemId)).isGreaterThan(0);
    }

    // Integration: happy path unaffected

    @Test
    void claim_happyPath_returns200() {
        given().queryParam("claimant", "alice")
                .put("/workitems/" + createWorkItem() + "/claim")
                .then().statusCode(200);
    }

    @Test
    void versionMonotonicallyIncreases_acrossMultipleOperations() {
        final String id = createWorkItem();
        final int v0 = version(id);
        given().queryParam("claimant", "alice").put("/workitems/" + id + "/claim").then().statusCode(200);
        final int v1 = version(id);
        given().queryParam("actor", "alice").put("/workitems/" + id + "/start").then().statusCode(200);
        final int v2 = version(id);
        assertThat(v1).isGreaterThan(v0);
        assertThat(v2).isGreaterThan(v1);
    }

    // Helpers

    private String createWorkItem() {
        return given().contentType(ContentType.JSON)
                .body("{\"title\":\"Lock test\",\"createdBy\":\"test\"}")
                .post("/workitems").then().statusCode(201).extract().path("id");
    }

    private int version(final String itemId) {
        return given().get("/workitems/" + itemId).then().statusCode(200).extract().path("version");
    }
}
