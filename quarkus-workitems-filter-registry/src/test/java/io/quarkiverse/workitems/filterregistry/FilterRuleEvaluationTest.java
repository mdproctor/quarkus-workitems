package io.quarkiverse.workitems.filterregistry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * E2E tests: WorkItem creation triggers FilterRegistryEngine → actions fire.
 * Issue #113, Epic #100.
 */
@QuarkusTest
class FilterRuleEvaluationTest {

    @Test
    void permanentFilter_firesOnAdd_whenConditionMatches() {
        // TestFilterProducer: score < 0.5 → label ai/test-label
        final String id = given().contentType(ContentType.JSON)
                .body("{\"title\":\"Low Conf\",\"createdBy\":\"agent\",\"confidenceScore\":0.3}")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", hasItem("ai/test-label"));
    }

    @Test
    void permanentFilter_doesNotFire_whenConditionDoesNotMatch() {
        final String id = given().contentType(ContentType.JSON)
                .body("{\"title\":\"High Conf\",\"createdBy\":\"agent\",\"confidenceScore\":0.9}")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", not(hasItem("ai/test-label")));
    }

    @Test
    void permanentFilter_doesNotFire_whenScoreIsNull() {
        final String id = given().contentType(ContentType.JSON)
                .body("{\"title\":\"No Score\",\"createdBy\":\"human\"}")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", not(hasItem("ai/test-label")));
    }

    @Test
    void dynamicRule_firesOnAdd_afterCreation() {
        // Create a dynamic rule: category == 'urgent-cat' → SET_PRIORITY CRITICAL
        final String cat = "urgent-cat-" + System.nanoTime();
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"e2e/urgent-" + cat + "\",\"enabled\":true," +
                        "\"condition\":\"workItem.category == '" + cat + "'\",\"events\":[\"ADD\"]," +
                        "\"actionsJson\":\"[{\\\"type\\\":\\\"SET_PRIORITY\\\"," +
                        "\\\"params\\\":{\\\"priority\\\":\\\"CRITICAL\\\"}}]\"}")
                .post("/filter-rules").then().statusCode(201);

        final String id = given().contentType(ContentType.JSON)
                .body("{\"title\":\"Urgent Item\",\"category\":\"" + cat + "\",\"createdBy\":\"sys\"}")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("priority", equalTo("CRITICAL"));
    }

    @Test
    void disabledPermanentFilter_doesNotFire_evenIfConditionMatches() {
        // Disable the apply-label filter
        given().contentType("application/json").body("{\"enabled\":false}")
                .queryParam("name", "test/apply-label")
                .put("/filter-rules/permanent/enabled")
                .then().statusCode(200);

        try {
            final String id = given().contentType(ContentType.JSON)
                    .body("{\"title\":\"Disabled Test\",\"createdBy\":\"agent\",\"confidenceScore\":0.1}")
                    .post("/workitems").then().statusCode(201).extract().path("id");

            given().get("/workitems/" + id).then().statusCode(200)
                    .body("labels.path", not(hasItem("ai/test-label")));
        } finally {
            // Always re-enable for subsequent tests
            given().contentType("application/json").body("{\"enabled\":true}")
                    .queryParam("name", "test/apply-label")
                    .put("/filter-rules/permanent/enabled")
                    .then().statusCode(200);
        }
    }

    @Test
    void filterEngine_isIdempotent_noLabelDuplicate_onMultipleEvents() {
        // Create WorkItem with low confidence — filter fires on ADD
        final String id = given().contentType(ContentType.JSON)
                .body("{\"title\":\"Idempotent\",\"createdBy\":\"agent\",\"confidenceScore\":0.3}")
                .post("/workitems").then().statusCode(201).extract().path("id");

        // Trigger an UPDATE event (claim the WorkItem) — filter subscribes to ADD only,
        // so it should NOT fire again and NOT duplicate the label
        given().put("/workitems/" + id + "/claim?claimant=reviewer").then().statusCode(200);

        // Only one label with that path
        final int labelCount = given().get("/workitems/" + id)
                .then().statusCode(200).extract()
                .jsonPath().getList("labels.findAll { it.path == 'ai/test-label' }").size();
        org.assertj.core.api.Assertions.assertThat(labelCount).isEqualTo(1);
    }
}
