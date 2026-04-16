package io.quarkiverse.workitems.queues.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class FilterResourceTest {

    @Test
    void createFilter_jexl_returnsId() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"HP intake","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"priority == 'HIGH'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"intake/triage"}]}""")
                .post("/filters").then().statusCode(201)
                .body("id", notNullValue()).body("active", equalTo(true));
    }

    @Test
    void listFilters_returnsCreated() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"List test","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"status == 'PENDING'",
                         "actions":[{"type":"APPLY_LABEL","labelPath":"intake"}]}""")
                .post("/filters").then().statusCode(201);
        given().get("/filters").then().statusCode(200).body("name", hasItem("List test"));
    }

    @Test
    void createFilter_lambdaLanguage_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Lambda","scope":"ORG","conditionLanguage":"lambda",
                         "conditionExpression":null,"actions":[]}""")
                .post("/filters").then().statusCode(400);
    }

    @Test
    void deleteFilter_removesIt() {
        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Delete me","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"true","actions":[]}""")
                .post("/filters").then().statusCode(201).extract().path("id");
        given().delete("/filters/" + id).then().statusCode(204);
    }

    @Test
    void adHocEval_matching_returnsTrue() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"conditionLanguage":"jexl","conditionExpression":"priority == 'HIGH'",
                         "workItem":{"title":"t","status":"PENDING","priority":"HIGH"}}""")
                .post("/filters/evaluate").then().statusCode(200).body("matches", equalTo(true));
    }

    @Test
    void adHocEval_nonMatching_returnsFalse() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"conditionLanguage":"jexl","conditionExpression":"priority == 'HIGH'",
                         "workItem":{"title":"t","status":"PENDING","priority":"NORMAL"}}""")
                .post("/filters/evaluate").then().statusCode(200).body("matches", equalTo(false));
    }

    @Test
    void lambdaFilters_notInRestList() {
        given().get("/filters").then().statusCode(200)
                .body("conditionLanguage", not(hasItem("lambda")));
    }

    @Test
    void updateFilter_changesExpression() {
        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Update test","scope":"ORG","conditionLanguage":"jexl",
                         "conditionExpression":"priority == 'HIGH'","actions":[]}""")
                .post("/filters").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Update test","conditionExpression":"priority == 'NORMAL'"}""")
                .put("/filters/" + id)
                .then().statusCode(200).body("name", equalTo("Update test"));
    }
}
