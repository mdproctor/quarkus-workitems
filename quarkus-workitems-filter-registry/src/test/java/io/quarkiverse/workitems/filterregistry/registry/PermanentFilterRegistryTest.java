package io.quarkiverse.workitems.filterregistry.registry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for permanent (CDI-produced) filter rule management via REST.
 * Issue #113, Epic #100.
 */
@QuarkusTest
class PermanentFilterRegistryTest {

    @Test
    void listPermanent_includesTestProducedFilters() {
        given().get("/filter-rules/permanent")
                .then().statusCode(200)
                .body("name", hasItems("test/apply-label", "test/override-groups", "test/set-priority"));
    }

    @Test
    void togglePermanent_disablesFilter_atRuntime() {
        given().contentType("application/json").body("{\"enabled\":false}")
                .queryParam("name", "test/apply-label")
                .put("/filter-rules/permanent/enabled")
                .then().statusCode(200)
                .body("enabled", equalTo(false));

        // Re-enable for subsequent tests
        given().contentType("application/json").body("{\"enabled\":true}")
                .queryParam("name", "test/apply-label")
                .put("/filter-rules/permanent/enabled")
                .then().statusCode(200)
                .body("enabled", equalTo(true));
    }

    @Test
    void togglePermanent_returns404_forUnknownFilter() {
        given().contentType("application/json").body("{\"enabled\":false}")
                .queryParam("name", "nonexistent-filter")
                .put("/filter-rules/permanent/enabled")
                .then().statusCode(404);
    }

    @Test
    void togglePermanent_returns400_whenEnabledFieldMissing() {
        given().contentType("application/json").body("{\"something\":\"else\"}")
                .queryParam("name", "test/apply-label")
                .put("/filter-rules/permanent/enabled")
                .then().statusCode(400);
    }
}
