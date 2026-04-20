package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifies that the OpenAPI spec is generated and contains expected operations.
 */
@QuarkusTest
class OpenApiTest {

    @Test
    void openApiEndpoint_returns200() {
        given().get("/q/openapi").then().statusCode(200);
    }

    @Test
    void openApiSpec_containsWorkItemsPath() {
        given().get("/q/openapi")
                .then().statusCode(200)
                .body(containsString("/workitems"));
    }

    @Test
    void openApiSpec_containsInboxSummaryPath() {
        given().get("/q/openapi")
                .then().statusCode(200)
                .body(containsString("inbox/summary"));
    }

    @Test
    void openApiSpec_containsTemplatesPath() {
        given().get("/q/openapi")
                .then().statusCode(200)
                .body(containsString("workitem-templates"));
    }

    @Test
    void openApiSpec_containsRelationsPath() {
        given().get("/q/openapi")
                .then().statusCode(200)
                .body(containsString("relations"));
    }
}
