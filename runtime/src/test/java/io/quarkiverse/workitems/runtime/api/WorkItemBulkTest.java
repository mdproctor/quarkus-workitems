package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class WorkItemBulkTest {

    // ── Bulk claim ────────────────────────────────────────────────────────────

    @Test
    void bulkClaim_claimsAllItems() {
        final List<String> ids = createItems(3);
        given().contentType(ContentType.JSON)
                .body(bulkBody("claim", ids, "alice", null))
                .post("/workitems/bulk")
                .then().statusCode(200)
                .body("$", hasSize(3))
                .body("[0].status", equalTo("ok"))
                .body("[1].status", equalTo("ok"))
                .body("[2].status", equalTo("ok"));

        // Verify actually claimed
        ids.forEach(id -> given().get("/workitems/" + id).then().body("status", equalTo("ASSIGNED")));
    }

    @Test
    void bulkClaim_partialSuccess_continuesOnFailure() {
        final List<String> ids = createItems(2);
        // Pre-claim the first one so it's no longer PENDING
        given().queryParam("claimant", "bob").put("/workitems/" + ids.get(0) + "/claim").then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body(bulkBody("claim", ids, "alice", null))
                .post("/workitems/bulk")
                .then().statusCode(200)
                .body("$", hasSize(2))
                .body("[0].status", equalTo("error")) // already claimed
                .body("[1].status", equalTo("ok")); // successfully claimed
    }

    // ── Bulk cancel ───────────────────────────────────────────────────────────

    @Test
    void bulkCancel_cancelsAllItems() {
        final List<String> ids = createItems(2);
        given().contentType(ContentType.JSON)
                .body(bulkBody("cancel", ids, "admin", "batch cancel"))
                .post("/workitems/bulk")
                .then().statusCode(200)
                .body("[0].status", equalTo("ok"))
                .body("[1].status", equalTo("ok"));

        ids.forEach(id -> given().get("/workitems/" + id).then().body("status", equalTo("CANCELLED")));
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    void bulk_returns400_forUnknownOperation() {
        given().contentType(ContentType.JSON)
                .body(bulkBody("invalidOp", createItems(1), "alice", null))
                .post("/workitems/bulk")
                .then().statusCode(400);
    }

    @Test
    void bulk_returns400_whenIdsEmpty() {
        given().contentType(ContentType.JSON)
                .body("{\"operation\":\"claim\",\"workItemIds\":[],\"actorId\":\"alice\"}")
                .post("/workitems/bulk")
                .then().statusCode(400);
    }

    @Test
    void bulk_returns400_whenExceedsMaxBatchSize() {
        final List<String> tooMany = IntStream.range(0, 101)
                .mapToObj(i -> "00000000-0000-0000-0000-" + String.format("%012d", i))
                .toList();
        given().contentType(ContentType.JSON)
                .body(bulkBody("claim", tooMany, "alice", null))
                .post("/workitems/bulk")
                .then().statusCode(400);
    }

    // ── Per-item result shape ─────────────────────────────────────────────────

    @Test
    void bulk_resultContainsIdAndStatus() {
        final List<String> ids = createItems(1);
        given().contentType(ContentType.JSON)
                .body(bulkBody("claim", ids, "alice", null))
                .post("/workitems/bulk")
                .then().statusCode(200)
                .body("[0].id", equalTo(ids.get(0)))
                .body("[0].status", equalTo("ok"));
    }

    @Test
    void bulk_errorResult_containsErrorMessage() {
        final List<String> ids = List.of("00000000-0000-0000-0000-000000000000");
        given().contentType(ContentType.JSON)
                .body(bulkBody("claim", ids, "alice", null))
                .post("/workitems/bulk")
                .then().statusCode(200)
                .body("[0].status", equalTo("error"))
                .body("[0].error", org.hamcrest.Matchers.notNullValue());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> createItems(final int count) {
        return IntStream.range(0, count).mapToObj(i -> (String) given().contentType(ContentType.JSON)
                .body("{\"title\":\"Bulk item " + i + "\",\"createdBy\":\"test\"}")
                .post("/workitems").then().statusCode(201).extract().path("id")).toList();
    }

    private String bulkBody(final String op, final List<String> ids,
            final String actorId, final String reason) {
        final StringBuilder sb = new StringBuilder("{\"operation\":\"").append(op).append("\",");
        sb.append("\"workItemIds\":[");
        sb.append(String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList()));
        sb.append("],\"actorId\":\"").append(actorId).append("\"");
        if (reason != null)
            sb.append(",\"reason\":\"").append(reason).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
