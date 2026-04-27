package io.quarkiverse.work.ledger.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkiverse.ledger.runtime.service.TrustScoreJob;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemPriority;
import io.quarkiverse.work.runtime.service.WorkItemService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;

/**
 * REST integration tests for {@code GET /workitems/actors/{actorId}/trust}.
 *
 * <p>
 * Tests verify that the endpoint returns correct HTTP status codes and response
 * body shapes after the {@link TrustScoreJob} has (or has not) been run.
 *
 * <p>
 * NOT annotated with {@code @TestTransaction}: {@link TrustScoreJob#runComputation()}
 * is {@code @Transactional} and must commit before the HTTP {@code GET /trust} call
 * can see the written score. Each test uses distinct actor IDs to prevent cross-test
 * contamination. The {@code trust-score.enabled=true} flag is set in
 * {@code test/resources/application.properties}.
 */
@QuarkusTest
class ActorTrustResourceTest {

    @Inject
    WorkItemService workItemService;

    @Inject
    TrustScoreJob trustScoreJob;

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a WorkItem and drives it through the full happy-path lifecycle.
     *
     * @param actor the actor identifier used for claim, start, and complete
     * @return the UUID of the completed WorkItem
     */
    private UUID createAndCompleteWorkItem(final String actor) {
        final WorkItemCreateRequest req = new WorkItemCreateRequest(
                "Trust REST test", null, null, null,
                WorkItemPriority.NORMAL, null, null, null, null,
                "system", null, null, null, null, null, null, null, null, null);
        final WorkItem wi = workItemService.create(req);
        workItemService.claim(wi.id, actor);
        workItemService.start(wi.id, actor);
        workItemService.complete(wi.id, actor, "{\"approved\":true}");
        return wi.id;
    }

    // -------------------------------------------------------------------------
    // 200 — score present after job run
    // -------------------------------------------------------------------------

    @Test
    void getActorTrust_afterComputation_returns200WithScore() {
        createAndCompleteWorkItem("alice");

        trustScoreJob.runComputation();

        given()
                .when().get("/workitems/actors/alice/trust")
                .then()
                .statusCode(200)
                .body("actorId", equalTo("alice"))
                .body("trustScore", notNullValue())
                .body("decisionCount", notNullValue());

        // Also assert numeric ranges via AssertJ
        final JsonPath json = given()
                .when().get("/workitems/actors/alice/trust")
                .then()
                .statusCode(200)
                .extract().jsonPath();

        final double trustScore = json.getDouble("trustScore");
        final int decisionCount = json.getInt("decisionCount");

        assertThat(trustScore).isGreaterThanOrEqualTo(0.0);
        assertThat(trustScore).isLessThanOrEqualTo(1.0);
        assertThat(decisionCount).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // 404 — unknown actor
    // -------------------------------------------------------------------------

    @Test
    void getActorTrust_unknownActor_returns404() {
        given()
                .when().get("/workitems/actors/nobody/trust")
                .then()
                .statusCode(404)
                .body("error", notNullValue());
    }

    // -------------------------------------------------------------------------
    // 200 — all fields present
    // -------------------------------------------------------------------------

    @Test
    void getActorTrust_response_hasAllFields() {
        final String actor = "carol-" + UUID.randomUUID().toString().substring(0, 8);
        createAndCompleteWorkItem(actor);

        trustScoreJob.runComputation();

        final ValidatableResponse response = given()
                .when().get("/workitems/actors/" + actor + "/trust")
                .then()
                .statusCode(200);

        // All documented fields must be present and non-null
        response
                .body("actorId", notNullValue())
                .body("actorType", notNullValue())
                .body("trustScore", notNullValue())
                .body("decisionCount", notNullValue())
                .body("overturnedCount", notNullValue())
                .body("attestationPositive", notNullValue())
                .body("attestationNegative", notNullValue())
                .body("lastComputedAt", notNullValue());

        // actorId must match
        response.body("actorId", equalTo(actor));
    }

    // -------------------------------------------------------------------------
    // 404 — job not yet run
    // -------------------------------------------------------------------------

    @Test
    void getActorTrust_beforeJobRuns_returns404() {
        // Use a unique actor name — alice may already have a score from a prior test
        final String actor = "nojob-" + UUID.randomUUID().toString().substring(0, 8);
        createAndCompleteWorkItem(actor);

        // runComputation() is NOT called — no score row written yet → 404
        given()
                .when().get("/workitems/actors/" + actor + "/trust")
                .then()
                .statusCode(404);
    }
}
