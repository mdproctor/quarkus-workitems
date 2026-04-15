package io.quarkiverse.tarkus.it;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;

/**
 * Black-box integration tests run against the packaged artifact (JVM uber-jar or native binary).
 * These tests exercise the full HTTP stack without access to CDI internals.
 * <p>
 * In JVM mode: {@code mvn verify -pl runtime}
 * In native mode: {@code JAVA_HOME=<graalvm> mvn verify -Pnative -pl runtime}
 */
@QuarkusIntegrationTest
class WorkItemNativeIT {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a minimal WorkItem and returns its id. */
    private String createWorkItem(String title) {
        return given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "%s",
                          "priority": "NORMAL",
                          "createdBy": "native-test"
                        }
                        """.formatted(title))
                .when().post("/tarkus/workitems")
                .then().statusCode(201)
                .extract().path("id");
    }

    // -------------------------------------------------------------------------
    // POST / — create
    // -------------------------------------------------------------------------

    @Test
    void create_returns201WithLocationAndPendingStatus() {
        String id = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Native create test",
                          "priority": "HIGH",
                          "createdBy": "system"
                        }
                        """)
                .when().post("/tarkus/workitems")
                .then()
                .statusCode(201)
                .header("Location", containsString("/tarkus/workitems/"))
                .body("id", notNullValue())
                .body("status", equalTo("PENDING"))
                .body("priority", equalTo("HIGH"))
                .body("createdBy", equalTo("system"))
                .extract().path("id");

        assertThat(id).isNotNull();
    }

    @Test
    void create_appliesDefaultExpiresAt() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"title":"Default expiry test","priority":"NORMAL","createdBy":"system"}
                        """)
                .when().post("/tarkus/workitems")
                .then()
                .statusCode(201)
                .body("expiresAt", notNullValue());
    }

    @Test
    void create_withCandidateGroups_storesThem() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Group task native",
                          "candidateGroups": "finance,leads",
                          "priority": "NORMAL",
                          "createdBy": "system"
                        }
                        """)
                .when().post("/tarkus/workitems")
                .then()
                .statusCode(201)
                .body("candidateGroups", equalTo("finance,leads"));
    }

    // -------------------------------------------------------------------------
    // GET / and GET /{id}
    // -------------------------------------------------------------------------

    @Test
    void listAll_returnsNonNullArray() {
        createWorkItem("List test native");
        given()
                .when().get("/tarkus/workitems")
                .then()
                .statusCode(200)
                .body("size()", greaterThanOrEqualTo(1));
    }

    @Test
    void getById_returnsWorkItemWithAuditTrail() {
        String id = createWorkItem("Get by id native");
        given()
                .when().get("/tarkus/workitems/{id}", id)
                .then()
                .statusCode(200)
                .body("id", equalTo(id))
                .body("status", equalTo("PENDING"))
                .body("auditTrail", notNullValue())
                .body("auditTrail.size()", greaterThanOrEqualTo(1))
                .body("auditTrail[0].event", equalTo("CREATED"));
    }

    @Test
    void getById_unknownId_returns404WithErrorBody() {
        given()
                .when().get("/tarkus/workitems/{id}", "00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404)
                .body("error", notNullValue())
                .body("error", containsString("not found"));
    }

    // -------------------------------------------------------------------------
    // Full happy path lifecycle
    // -------------------------------------------------------------------------

    @Test
    void fullHappyPath_createClaimStartComplete() {
        String id = createWorkItem("Full lifecycle native");

        // claim
        given().queryParam("claimant", "alice")
                .when().put("/tarkus/workitems/{id}/claim", id)
                .then().statusCode(200).body("status", equalTo("ASSIGNED"));

        // start
        given().queryParam("actor", "alice")
                .when().put("/tarkus/workitems/{id}/start", id)
                .then().statusCode(200).body("status", equalTo("IN_PROGRESS"));

        // complete
        given().contentType(ContentType.JSON)
                .queryParam("actor", "alice")
                .body("{\"resolution\":\"{\\\"approved\\\":true}\"}")
                .when().put("/tarkus/workitems/{id}/complete", id)
                .then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("resolution", notNullValue());
    }

    @Test
    void rejectPath_claimThenReject() {
        String id = createWorkItem("Reject native");
        given().queryParam("claimant", "bob")
                .when().put("/tarkus/workitems/{id}/claim", id);
        given().contentType(ContentType.JSON)
                .queryParam("actor", "bob")
                .body("{\"reason\":\"out of scope\"}")
                .when().put("/tarkus/workitems/{id}/reject", id)
                .then().statusCode(200).body("status", equalTo("REJECTED"));
    }

    @Test
    void delegatePath_claimThenDelegate() {
        String id = createWorkItem("Delegate native");
        given().queryParam("claimant", "alice")
                .when().put("/tarkus/workitems/{id}/claim", id);
        given().contentType(ContentType.JSON)
                .queryParam("actor", "alice")
                .body("{\"to\":\"bob\"}")
                .when().put("/tarkus/workitems/{id}/delegate", id)
                .then().statusCode(200)
                .body("status", equalTo("PENDING"))
                .body("assigneeId", equalTo("bob"));
    }

    @Test
    void releasePath_claimThenRelease() {
        String id = createWorkItem("Release native");
        given().queryParam("claimant", "alice")
                .when().put("/tarkus/workitems/{id}/claim", id);
        given().queryParam("actor", "alice")
                .when().put("/tarkus/workitems/{id}/release", id)
                .then().statusCode(200)
                .body("status", equalTo("PENDING"))
                .body("assigneeId", nullValue());
    }

    @Test
    void suspendResumePath_fullCycle() {
        String id = createWorkItem("Suspend resume native");
        given().queryParam("claimant", "alice")
                .when().put("/tarkus/workitems/{id}/claim", id);
        given().queryParam("actor", "alice")
                .when().put("/tarkus/workitems/{id}/start", id);
        given().contentType(ContentType.JSON)
                .queryParam("actor", "alice")
                .body("{\"reason\":\"blocked\"}")
                .when().put("/tarkus/workitems/{id}/suspend", id)
                .then().statusCode(200).body("status", equalTo("SUSPENDED"));

        given().queryParam("actor", "alice")
                .when().put("/tarkus/workitems/{id}/resume", id)
                .then().statusCode(200).body("status", equalTo("IN_PROGRESS"));
    }

    @Test
    void cancelPath_cancelFromPending() {
        String id = createWorkItem("Cancel native");
        given().contentType(ContentType.JSON)
                .queryParam("actor", "admin")
                .body("{\"reason\":\"no longer needed\"}")
                .when().put("/tarkus/workitems/{id}/cancel", id)
                .then().statusCode(200).body("status", equalTo("CANCELLED"));
    }

    // -------------------------------------------------------------------------
    // Inbox query
    // -------------------------------------------------------------------------

    @Test
    void inbox_noParams_returns200() {
        createWorkItem("Inbox native");
        given()
                .when().get("/tarkus/workitems/inbox")
                .then().statusCode(200);
    }

    @Test
    void inbox_filterByCandidateGroup_findsItem() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Inbox group native",
                          "candidateGroups": "native-team",
                          "priority": "NORMAL",
                          "createdBy": "system"
                        }
                        """)
                .when().post("/tarkus/workitems");

        List<String> ids = given()
                .queryParam("candidateGroup", "native-team")
                .when().get("/tarkus/workitems/inbox")
                .then().statusCode(200)
                .extract().jsonPath().getList("id");

        assertThat(ids).isNotEmpty();
    }

    @Test
    void inbox_filterByStatus_pending() {
        createWorkItem("Inbox status native");
        given()
                .queryParam("status", "PENDING")
                .when().get("/tarkus/workitems/inbox")
                .then().statusCode(200);
    }

    @Test
    void inbox_filterByPriority_high() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"title\":\"High prio native\",\"priority\":\"HIGH\",\"createdBy\":\"system\"}")
                .when().post("/tarkus/workitems");

        given()
                .queryParam("priority", "HIGH")
                .when().get("/tarkus/workitems/inbox")
                .then().statusCode(200);
    }

    // -------------------------------------------------------------------------
    // Error cases — validate exception mappers work in native
    // -------------------------------------------------------------------------

    @Test
    void claimAlreadyAssigned_returns409WithErrorBody() {
        String id = createWorkItem("409 native");
        given().queryParam("claimant", "alice")
                .when().put("/tarkus/workitems/{id}/claim", id);
        given().queryParam("claimant", "bob")
                .when().put("/tarkus/workitems/{id}/claim", id)
                .then()
                .statusCode(409)
                .body("error", notNullValue());
    }

    @Test
    void completePendingItem_returns409() {
        String id = createWorkItem("409 complete native");
        given().contentType(ContentType.JSON)
                .queryParam("actor", "alice")
                .body("{\"resolution\":\"done\"}")
                .when().put("/tarkus/workitems/{id}/complete", id)
                .then().statusCode(409);
    }

    // -------------------------------------------------------------------------
    // Audit trail integrity in packaged mode
    // -------------------------------------------------------------------------

    @Test
    void auditTrailHasFourEntriesAfterFullPath() {
        String id = createWorkItem("Audit native");
        given().queryParam("claimant", "alice").when().put("/tarkus/workitems/{id}/claim", id);
        given().queryParam("actor", "alice").when().put("/tarkus/workitems/{id}/start", id);
        given().contentType(ContentType.JSON).queryParam("actor", "alice")
                .body("{\"resolution\":\"done\"}")
                .when().put("/tarkus/workitems/{id}/complete", id);

        given()
                .when().get("/tarkus/workitems/{id}", id)
                .then().statusCode(200)
                .body("auditTrail.size()", equalTo(4))
                .body("auditTrail[0].event", equalTo("CREATED"))
                .body("auditTrail[1].event", equalTo("ASSIGNED"))
                .body("auditTrail[2].event", equalTo("STARTED"))
                .body("auditTrail[3].event", equalTo("COMPLETED"));
    }
}
