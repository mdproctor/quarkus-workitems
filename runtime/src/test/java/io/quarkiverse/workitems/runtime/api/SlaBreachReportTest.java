package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration and E2E tests for GET /workitems/reports/sla-breaches.
 *
 * <p>
 * A WorkItem is "SLA breached" if it was completed after its expiresAt
 * (late completion) or is still open past its expiresAt. Tests use
 * explicit expiresAt values to control breach state deterministically.
 *
 * <p>
 * Issue #110, Epic #99.
 */
@QuarkusTest
class SlaBreachReportTest {

    // ── GET /workitems/reports/sla-breaches — structure ───────────────────────

    @Test
    void report_returns200_withExpectedStructure() {
        given().get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items", notNullValue())
                .body("summary", notNullValue())
                .body("summary.totalBreached", notNullValue())
                .body("summary.avgBreachDurationMinutes", notNullValue())
                .body("summary.byCategory", notNullValue());
    }

    // ── Breached item detection ───────────────────────────────────────────────

    @Test
    void report_includes_workItem_completedAfterExpiry() {
        final String category = "breach-cat-" + System.nanoTime();
        // Set expiresAt 2 minutes in the past so it's already expired at completion time
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();

        final String id = createWorkItemWithExpiry("Breached WI", category, expiresAt);
        claimStartComplete(id, "reviewer");

        // This WorkItem completed after its expiresAt → breach
        given().get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", org.hamcrest.Matchers.hasItem(id));
    }

    @Test
    void report_excludes_workItem_completedBeforeExpiry() {
        final String category = "no-breach-cat-" + System.nanoTime();
        // expiresAt far in the future
        final String expiresAt = Instant.now().plus(24, ChronoUnit.HOURS).toString();

        final String id = createWorkItemWithExpiry("On-time WI", category, expiresAt);
        claimStartComplete(id, "reviewer");

        // Should NOT appear as breached
        given().get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", not(org.hamcrest.Matchers.hasItem(id)));
    }

    @Test
    void report_includes_openWorkItem_pastExpiry() {
        // An open WorkItem whose deadline has passed is also breached
        final String category = "open-breach-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES).toString();

        final String id = createWorkItemWithExpiry("Open Past Deadline", category, expiresAt);
        // Do NOT complete — leave it open (still breached)

        given().get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", org.hamcrest.Matchers.hasItem(id));
    }

    // ── Filter by date range ──────────────────────────────────────────────────

    @Test
    void filterByFrom_excludesItemsWhoseExpiryIsBefore_from() {
        final String category = "from-filter-" + System.nanoTime();
        // expiresAt 10 minutes in the past
        final String expiresAt = Instant.now().minus(10, ChronoUnit.MINUTES).toString();
        final String id = createWorkItemWithExpiry("Old Breach", category, expiresAt);
        claimStartComplete(id, "reviewer");

        // from = far future: this item's expiresAt should be excluded
        given().queryParam("from", "2099-01-01T00:00:00Z")
                .get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items", empty());
    }

    @Test
    void filterByTo_excludesItemsWhoseExpiryIsAfter_to() {
        final String category = "to-filter-" + System.nanoTime();
        // expiresAt 2 minutes in past (would be in the breach list without filter)
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();
        final String id = createWorkItemWithExpiry("Future Breach", category, expiresAt);
        claimStartComplete(id, "reviewer");

        // to = far past: should not match
        given().queryParam("to", "2000-01-01T00:00:00Z")
                .get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", not(org.hamcrest.Matchers.hasItem(id)));
    }

    // ── Filter by category ────────────────────────────────────────────────────

    @Test
    void filterByCategory_returnsOnlyThatCategory() {
        final String catA = "sla-a-" + System.nanoTime();
        final String catB = "sla-b-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();

        final String idA = createWorkItemWithExpiry("Cat A Breach", catA, expiresAt);
        final String idB = createWorkItemWithExpiry("Cat B Breach", catB, expiresAt);
        claimStartComplete(idA, "reviewer");
        claimStartComplete(idB, "reviewer");

        given().queryParam("category", catA)
                .get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", org.hamcrest.Matchers.hasItem(idA))
                .body("items.workItemId", not(org.hamcrest.Matchers.hasItem(idB)));
    }

    // ── Filter by priority ────────────────────────────────────────────────────

    @Test
    void filterByPriority_returnsOnlyMatchingPriority() {
        final String cat = "prio-breach-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();

        final String highId = createWorkItemWithExpiryAndPriority("High Breach", cat, expiresAt, "HIGH");
        final String lowId = createWorkItemWithExpiryAndPriority("Low Breach", cat, expiresAt, "LOW");
        claimStartComplete(highId, "reviewer");
        claimStartComplete(lowId, "reviewer");

        given().queryParam("priority", "HIGH")
                .get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", org.hamcrest.Matchers.hasItem(highId))
                .body("items.workItemId", not(org.hamcrest.Matchers.hasItem(lowId)));
    }

    // ── Response fields per breached item ─────────────────────────────────────

    @Test
    void breachedItem_hasRequiredFields() {
        final String category = "fields-breach-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();
        final String id = createWorkItemWithExpiry("Fields Test", category, expiresAt);
        claimStartComplete(id, "reviewer");

        given().queryParam("category", category)
                .get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].workItemId", equalTo(id))
                .body("items[0].category", equalTo(category))
                .body("items[0].priority", notNullValue())
                .body("items[0].expiresAt", notNullValue())
                .body("items[0].status", notNullValue())
                .body("items[0].breachDurationMinutes", notNullValue());
    }

    // ── Summary aggregates ────────────────────────────────────────────────────

    @Test
    void summary_totalBreached_matchesItemsCount() {
        final String cat = "summary-breach-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();

        createWorkItemWithExpiry("S1", cat, expiresAt);
        createWorkItemWithExpiry("S2", cat, expiresAt);

        // Breached items total should be >= 2
        final int total = given().queryParam("category", cat)
                .get("/workitems/reports/sla-breaches")
                .then().statusCode(200)
                .extract().path("summary.totalBreached");

        assertThat(total).isGreaterThanOrEqualTo(2);
    }

    @Test
    void summary_byCategory_groupsBreachedItemsByCategory() {
        final String catX = "by-cat-x-" + System.nanoTime();
        final String catY = "by-cat-y-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(2, ChronoUnit.MINUTES).toString();

        createWorkItemWithExpiry("X1", catX, expiresAt);
        createWorkItemWithExpiry("Y1", catY, expiresAt);
        createWorkItemWithExpiry("Y2", catY, expiresAt);

        final io.restassured.response.Response resp = given()
                .get("/workitems/reports/sla-breaches")
                .then().statusCode(200)
                .extract().response();

        // byCategory should be a map with at least the categories we created
        assertThat((Object) resp.path("summary.byCategory." + catX)).isNotNull();
        assertThat((Object) resp.path("summary.byCategory." + catY)).isNotNull();
    }

    @Test
    void summary_avgBreachDurationMinutes_isPositive_whenBreachesExist() {
        final String cat = "avg-breach-" + System.nanoTime();
        final String expiresAt = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
        final String id = createWorkItemWithExpiry("Avg Test", cat, expiresAt);
        claimStartComplete(id, "reviewer");

        final float avg = given().queryParam("category", cat)
                .get("/workitems/reports/sla-breaches")
                .then().statusCode(200)
                .extract().path("summary.avgBreachDurationMinutes");

        assertThat(avg).isGreaterThan(0);
    }

    // ── E2E: compliance scenario ──────────────────────────────────────────────

    @Test
    void e2e_slaBreachReport_mixedCompliance() {
        final String cat = "mixed-sla-" + System.nanoTime();
        final String pastExpiry = Instant.now().minus(3, ChronoUnit.MINUTES).toString();
        final String futureExpiry = Instant.now().plus(1, ChronoUnit.HOURS).toString();

        // Two items completed late (breach)
        final String breach1 = createWorkItemWithExpiry("Late 1", cat, pastExpiry);
        final String breach2 = createWorkItemWithExpiry("Late 2", cat, pastExpiry);
        claimStartComplete(breach1, "reviewer");
        claimStartComplete(breach2, "reviewer");

        // One item completed on time (no breach)
        final String onTime = createWorkItemWithExpiry("On Time", cat, futureExpiry);
        claimStartComplete(onTime, "reviewer");

        given().queryParam("category", cat)
                .get("/workitems/reports/sla-breaches")
                .then()
                .statusCode(200)
                .body("items.workItemId", org.hamcrest.Matchers.hasItem(breach1))
                .body("items.workItemId", org.hamcrest.Matchers.hasItem(breach2))
                .body("items.workItemId", not(org.hamcrest.Matchers.hasItem(onTime)))
                .body("summary.totalBreached", greaterThanOrEqualTo(2));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String createWorkItemWithExpiry(final String title, final String category,
            final String expiresAt) {
        return given().contentType(ContentType.JSON)
                .body("{\"title\":\"" + title + "\",\"category\":\"" + category
                        + "\",\"expiresAt\":\"" + expiresAt + "\",\"createdBy\":\"system\"}")
                .post("/workitems")
                .then().statusCode(201).extract().path("id");
    }

    private String createWorkItemWithExpiryAndPriority(final String title, final String category,
            final String expiresAt, final String priority) {
        return given().contentType(ContentType.JSON)
                .body("{\"title\":\"" + title + "\",\"category\":\"" + category
                        + "\",\"priority\":\"" + priority
                        + "\",\"expiresAt\":\"" + expiresAt + "\",\"createdBy\":\"system\"}")
                .post("/workitems")
                .then().statusCode(201).extract().path("id");
    }

    private void claimStartComplete(final String id, final String actor) {
        given().put("/workitems/" + id + "/claim?claimant=" + actor).then().statusCode(200);
        given().put("/workitems/" + id + "/start?actor=" + actor).then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}")
                .put("/workitems/" + id + "/complete?actor=" + actor).then().statusCode(200);
    }

    private static org.hamcrest.Matcher<Integer> greaterThanOrEqualTo(final int n) {
        return org.hamcrest.Matchers.greaterThanOrEqualTo(n);
    }
}
