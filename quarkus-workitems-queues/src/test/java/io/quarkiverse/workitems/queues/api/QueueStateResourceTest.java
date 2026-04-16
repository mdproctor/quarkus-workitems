package io.quarkiverse.workitems.queues.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class QueueStateResourceTest {

    @Test
    void setRelinquishable_true_succeeds() {
        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Soft assign test","createdBy":"alice","assigneeId":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("""
                {"relinquishable":true}""")
                .put("/workitems/" + id + "/relinquishable").then()
                .statusCode(200).body("relinquishable", equalTo(true));
    }

    @Test
    void setRelinquishable_false_clearsFlag() {
        var id = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Clear flag test","createdBy":"alice","assigneeId":"alice"}""")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON).body("""
                {"relinquishable":true}""")
                .put("/workitems/" + id + "/relinquishable").then().statusCode(200);

        given().contentType(ContentType.JSON).body("""
                {"relinquishable":false}""")
                .put("/workitems/" + id + "/relinquishable").then()
                .statusCode(200).body("relinquishable", equalTo(false));
    }

    @Test
    void relinquishable_unknownWorkItem_returns404() {
        given().contentType(ContentType.JSON).body("""
                {"relinquishable":true}""")
                .put("/workitems/00000000-0000-0000-0000-000000000000/relinquishable").then()
                .statusCode(404);
    }
}
