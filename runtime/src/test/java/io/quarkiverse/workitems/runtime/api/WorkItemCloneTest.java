package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class WorkItemCloneTest {

    @Test
    void clone_returns201_withNewId() {
        final String sourceId = createFull();

        final String cloneId = given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("status", equalTo("PENDING"))
                .extract().path("id");

        // Different ID from source
        org.assertj.core.api.Assertions.assertThat(cloneId).isNotEqualTo(sourceId);
    }

    @Test
    void clone_copiesOperationalFields() {
        final String sourceId = createFull();

        given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("title", containsString("Full item"))
                .body("category", equalTo("test-category"))
                .body("priority", equalTo("HIGH"))
                .body("candidateGroups", equalTo("team-a"))
                .body("payload", equalTo("{\"key\":\"value\"}"))
                .body("status", equalTo("PENDING"));
    }

    @Test
    void clone_defaultTitle_appendsCopySuffix() {
        final String sourceId = createFull();

        given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("title", containsString("(copy)"));
    }

    @Test
    void clone_withTitleOverride_usesProvidedTitle() {
        final String sourceId = createFull();

        given().contentType(ContentType.JSON)
                .body("{\"title\":\"Custom clone title\",\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("title", equalTo("Custom clone title"));
    }

    @Test
    void clone_doesNotCopyAssignee_orOwner() {
        final String sourceId = given().contentType(ContentType.JSON)
                .body("{\"title\":\"Assigned item\",\"createdBy\":\"sys\",\"assigneeId\":\"bob\"}")
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("assigneeId", nullValue())
                .body("owner", nullValue());
    }

    @Test
    void clone_doesNotCopyResolution_orDelegationChain() {
        // Complete the source through claim → start → complete lifecycle
        final String sourceId = createFull();
        given().queryParam("claimant", "bob").put("/workitems/" + sourceId + "/claim").then().statusCode(200);
        given().queryParam("actor", "bob").put("/workitems/" + sourceId + "/start").then().statusCode(200);
        given().contentType(ContentType.JSON)
                .body("{\"resolution\":\"{}\"}")
                .queryParam("actor", "bob")
                .put("/workitems/" + sourceId + "/complete").then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("status", equalTo("PENDING"))
                .body("resolution", nullValue())
                .body("delegationChain", nullValue());
    }

    @Test
    void clone_copiesManualLabels_notInferred() {
        // Item with a MANUAL label
        final String sourceId = given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Labelled","createdBy":"sys",
                         "labels":[{"path":"legal/review","persistence":"MANUAL","appliedBy":"alice"}]}
                        """)
                .post("/workitems").then().statusCode(201).extract().path("id");

        given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/" + sourceId + "/clone")
                .then().statusCode(201)
                .body("labels.path", hasItem("legal/review"))
                .body("labels.findAll{it.persistence=='MANUAL'}.size()", equalTo(1));
    }

    @Test
    void clone_returns404_forUnknownSource() {
        given().contentType(ContentType.JSON)
                .body("{\"createdBy\":\"alice\"}")
                .post("/workitems/00000000-0000-0000-0000-000000000000/clone")
                .then().statusCode(404);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private String createFull() {
        return given().contentType(ContentType.JSON)
                .body("""
                        {"title":"Full item","category":"test-category","priority":"HIGH",
                         "candidateGroups":"team-a","createdBy":"sys",
                         "payload":"{\\"key\\":\\"value\\"}"}
                        """)
                .post("/workitems").then().statusCode(201).extract().path("id");
    }
}
