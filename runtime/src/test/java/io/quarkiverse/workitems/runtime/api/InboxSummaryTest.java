package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for GET /workitems/inbox/summary.
 */
@QuarkusTest
class InboxSummaryTest {

    @Test
    void summary_returns200_withExpectedShape() {
        given().get("/workitems/inbox/summary")
                .then()
                .statusCode(200)
                .body("total", notNullValue())
                .body("byStatus", notNullValue())
                .body("byPriority", notNullValue())
                .body("overdue", notNullValue())
                .body("claimDeadlineBreached", notNullValue());
    }

    @Test
    void summary_countsCreatedWorkItem() {
        given().contentType(ContentType.JSON)
                .body("{\"title\":\"Summary test item\",\"createdBy\":\"test\",\"priority\":\"HIGH\"}")
                .post("/workitems").then().statusCode(201);

        given().get("/workitems/inbox/summary")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("byStatus.PENDING", greaterThanOrEqualTo(1))
                .body("byPriority.HIGH", greaterThanOrEqualTo(1));
    }

    @Test
    void summary_withAssigneeFilter_scopesToAssignee() {
        final String unique = "summary-assignee-" + java.util.UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body("{\"title\":\"Assignee summary test\",\"createdBy\":\"test\",\"assigneeId\":\"" + unique + "\"}")
                .post("/workitems").then().statusCode(201);

        // Filter to this specific assignee
        given().queryParam("assignee", unique)
                .get("/workitems/inbox/summary")
                .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("byStatus.PENDING", equalTo(1));
    }

    @Test
    void summary_withCategoryFilter_scopesToCategory() {
        final String cat = "summary-cat-" + java.util.UUID.randomUUID();
        given().contentType(ContentType.JSON)
                .body("{\"title\":\"Cat summary test\",\"createdBy\":\"test\",\"category\":\"" + cat + "\"}")
                .post("/workitems").then().statusCode(201);

        given().queryParam("category", cat)
                .get("/workitems/inbox/summary")
                .then()
                .statusCode(200)
                .body("total", equalTo(1));
    }
}
