package io.quarkiverse.workitems.queues.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class FullPipelineTest {

    @Test
    void savedJexlFilter_firesOnWorkItemCreation_appliesInferredLabel() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"HP triage","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"priority == 'HIGH'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"intake/triage"}]}""")
                .post("/filters").then().statusCode(201);

        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Pipeline test","priority":"HIGH","createdBy":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", hasItem("intake/triage"))
                .body("labels.findAll { it.path == 'intake/triage' }[0].persistence",
                        equalTo("INFERRED"));
    }

    @Test
    void savedFilter_notMatchingPriority_doesNotApplyLabel() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"HP only","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"priority == 'HIGH'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"priority/high"}]}""")
                .post("/filters").then().statusCode(201);

        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Normal priority item","priority":"NORMAL","createdBy":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", not(hasItem("priority/high")));
    }

    @Test
    void deleteFilter_cascadesInferredLabels() {
        // Use a unique label path to avoid cross-test contamination from filters in other test classes
        var filterId = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Cascade filter","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"category == 'cascade-test-unique'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"cascade/unique-marker"}]}""")
                .post("/filters").then().statusCode(201).extract().path("id");

        var workItemId = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Cascade test","category":"cascade-test-unique","createdBy":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        // Verify INFERRED label applied
        given().get("/workitems/" + workItemId).then().statusCode(200)
                .body("labels.path", hasItem("cascade/unique-marker"));

        // Delete filter — cascade removes INFERRED label
        given().delete("/filters/" + filterId).then().statusCode(204);

        // Verify label gone
        given().get("/workitems/" + workItemId).then().statusCode(200)
                .body("labels.path", not(hasItem("cascade/unique-marker")));
    }

    @Test
    void deleteOneFilter_labelSurvives_whenOtherFilterStillMatches() {
        // Both filters apply the same label — deleting one should not remove the label
        // if the other filter still matches the WorkItem
        var filterAId = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Survive A","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"priority == 'HIGH'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"shared/label-survive-test"}]}""")
                .post("/filters").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Survive B","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"status == 'PENDING'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"shared/label-survive-test"}]}""")
                .post("/filters").then().statusCode(201);

        // Create WorkItem matching both conditions (HIGH + PENDING)
        var workItemId = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Survive test","priority":"HIGH","createdBy":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        // Label should be present from at least one of the filters
        given().get("/workitems/" + workItemId).then().statusCode(200)
                .body("labels.path", hasItem("shared/label-survive-test"));

        // Delete filter A — filter B still matches PENDING, so the label should survive
        given().delete("/filters/" + filterAId).then().statusCode(204);

        given().get("/workitems/" + workItemId).then().statusCode(200)
                .body("labels.path", hasItem("shared/label-survive-test"));
    }

    @Test
    void updateWorkItem_statusChange_reEvaluatesFilters() {
        // Filter: PENDING → intake
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Pending only","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"status == 'PENDING'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"intake"}]}""")
                .post("/filters").then().statusCode(201);

        // Create PENDING WorkItem — should get intake label
        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Status change test","createdBy":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", hasItem("intake"));

        // Claim the WorkItem (PENDING → ASSIGNED) — claimant passed as query param
        given().put("/workitems/" + id + "/claim?claimant=bob")
                .then().statusCode(200);

        // Filter condition (status == PENDING) no longer matches — INFERRED label removed
        given().get("/workitems/" + id).then().statusCode(200)
                .body("labels.path", not(hasItem("intake")));
    }
}
