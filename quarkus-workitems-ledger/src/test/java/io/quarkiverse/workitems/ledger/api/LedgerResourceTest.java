package io.quarkiverse.workitems.ledger.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * REST integration tests for the Ledger endpoints.
 *
 * <p>
 * Tests {@code GET /workitems/{id}/ledger} and the provenance and
 * attestation sub-resources.
 *
 * <p>
 * {@code @TestTransaction} rolls back after each test — no ledger entries
 * or attestations persist across test boundaries.
 *
 * <p>
 * RED-phase: these tests compile only once the production REST resource and
 * related classes are in place.
 */
@QuarkusTest
@TestTransaction
class LedgerResourceTest {

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a minimal WorkItem via REST and returns its id string.
     * A 201 response is asserted as part of the setup; a failure here is a
     * fixture problem, not the test under examination.
     */
    private String createWorkItem() {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "title": "Ledger test item",
                            "priority": "NORMAL",
                            "createdBy": "system"
                        }
                        """)
                .when().post("/workitems")
                .then().statusCode(201)
                .extract().path("id");
    }

    /**
     * Claims a WorkItem via REST. Asserts 200.
     */
    private void claimWorkItem(final String id, final String claimant) {
        given()
                .when().put("/workitems/" + id + "/claim?claimant=" + claimant)
                .then().statusCode(200);
    }

    /**
     * Starts a WorkItem via REST. Asserts 200.
     */
    private void startWorkItem(final String id, final String actor) {
        given()
                .when().put("/workitems/" + id + "/start?actor=" + actor)
                .then().statusCode(200);
    }

    /**
     * Completes a WorkItem via REST. Asserts 200.
     */
    private void completeWorkItem(final String id, final String actor) {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        { "resolution": "Done" }
                        """)
                .when().put("/workitems/" + id + "/complete?actor=" + actor)
                .then().statusCode(200);
    }

    /**
     * Retrieves the first ledger entry id for a given WorkItem.
     */
    private String firstLedgerEntryId(final String workItemId) {
        final List<String> ids = given()
                .when().get("/workitems/" + workItemId + "/ledger")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");
        assertThat(ids).isNotEmpty();
        return ids.get(0);
    }

    // -------------------------------------------------------------------------
    // GET /workitems/{id}/ledger
    // -------------------------------------------------------------------------

    @Test
    void getLedger_afterCreate_returnsOneEntry() {
        final String id = createWorkItem();

        given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].eventType", equalTo("WorkItemCreated"));
    }

    @Test
    void getLedger_afterFullPath_returnsFourEntries() {
        final String id = createWorkItem();
        claimWorkItem(id, "alice");
        startWorkItem(id, "alice");
        completeWorkItem(id, "alice");

        final List<String> eventTypes = given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("$", hasSize(4))
                .extract().jsonPath().getList("eventType");

        assertThat(eventTypes).containsExactly(
                "WorkItemCreated",
                "WorkItemAssigned",
                "WorkItemStarted",
                "WorkItemCompleted");
    }

    @Test
    void getLedger_entryHasNonNullDigest() {
        final String id = createWorkItem();

        given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].digest", notNullValue());
    }

    @Test
    void getLedger_bothEntriesHaveDistinctNonNullDigests() {
        final String id = createWorkItem();
        claimWorkItem(id, "alice");

        final List<String> digests = given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .extract().jsonPath().getList("digest");

        assertThat(digests.get(0)).isNotNull();
        assertThat(digests.get(1)).isNotNull();
        assertThat(digests.get(0)).isNotEqualTo(digests.get(1));
    }

    @Test
    void getLedger_unknownWorkItem_returns404() {
        final String randomId = UUID.randomUUID().toString();

        given()
                .when().get("/workitems/" + randomId + "/ledger")
                .then()
                .statusCode(404);
    }

    @Test
    void getLedger_decisionContextPresent() {
        final String id = createWorkItem();

        given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].decisionContext", notNullValue());
    }

    @Test
    void getLedger_firstEntryHasNullPreviousHash() {
        final String id = createWorkItem();

        given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].previousHash", nullValue());
    }

    // -------------------------------------------------------------------------
    // PUT /workitems/{id}/ledger/provenance
    // -------------------------------------------------------------------------

    @Test
    void provenance_setsSourceEntityOnFirstEntry() {
        final String id = createWorkItem();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "sourceEntityId": "case-1",
                            "sourceEntityType": "CaseHub:Case",
                            "sourceEntitySystem": "casehub"
                        }
                        """)
                .when().put("/workitems/" + id + "/ledger/provenance")
                .then()
                .statusCode(200);

        given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].sourceEntityId", equalTo("case-1"))
                .body("[0].sourceEntityType", equalTo("CaseHub:Case"))
                .body("[0].sourceEntitySystem", equalTo("casehub"));
    }

    @Test
    void provenance_unknownWorkItem_returns404() {
        final String randomId = UUID.randomUUID().toString();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "sourceEntityId": "x",
                            "sourceEntityType": "T",
                            "sourceEntitySystem": "s"
                        }
                        """)
                .when().put("/workitems/" + randomId + "/ledger/provenance")
                .then()
                .statusCode(404);
    }

    // -------------------------------------------------------------------------
    // POST /workitems/{id}/ledger/{entryId}/attestations
    // -------------------------------------------------------------------------

    @Test
    void postAttestation_returns201() {
        final String id = createWorkItem();
        final String entryId = firstLedgerEntryId(id);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "attestorId": "alice",
                            "attestorType": "HUMAN",
                            "verdict": "SOUND",
                            "confidence": 0.9
                        }
                        """)
                .when().post("/workitems/" + id + "/ledger/" + entryId + "/attestations")
                .then()
                .statusCode(201);
    }

    @Test
    void postAttestation_appearsInGetLedger() {
        final String id = createWorkItem();
        final String entryId = firstLedgerEntryId(id);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "attestorId": "alice",
                            "attestorType": "HUMAN",
                            "verdict": "SOUND",
                            "confidence": 0.9
                        }
                        """)
                .when().post("/workitems/" + id + "/ledger/" + entryId + "/attestations")
                .then()
                .statusCode(201);

        given()
                .when().get("/workitems/" + id + "/ledger")
                .then()
                .statusCode(200)
                .body("[0].attestations", hasSize(1))
                .body("[0].attestations[0].attestorId", equalTo("alice"))
                .body("[0].attestations[0].verdict", equalTo("SOUND"));
    }

    @Test
    void postAttestation_unknownEntry_returns404() {
        final String id = createWorkItem();
        final String randomEntryId = UUID.randomUUID().toString();

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "attestorId": "alice",
                            "attestorType": "HUMAN",
                            "verdict": "SOUND",
                            "confidence": 0.9
                        }
                        """)
                .when().post("/workitems/" + id + "/ledger/" + randomEntryId + "/attestations")
                .then()
                .statusCode(404);
    }

    @Test
    void postAttestation_invalidVerdict_returns400() {
        final String id = createWorkItem();
        final String entryId = firstLedgerEntryId(id);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                            "attestorId": "alice",
                            "attestorType": "HUMAN",
                            "verdict": "INVALID",
                            "confidence": 0.9
                        }
                        """)
                .when().post("/workitems/" + id + "/ledger/" + entryId + "/attestations")
                .then()
                .statusCode(400);
    }
}
