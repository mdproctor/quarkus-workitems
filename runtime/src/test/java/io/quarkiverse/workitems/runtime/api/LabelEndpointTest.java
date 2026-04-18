package io.quarkiverse.workitems.runtime.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
class LabelEndpointTest {

    @Test
    void createWorkItem_withManualLabel_returnedInResponse() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Review contract",
                          "createdBy": "alice",
                          "labels": [
                            {"path": "legal/contracts", "persistence": "MANUAL", "appliedBy": "alice"}
                          ]
                        }
                        """)
                .post("/workitems")
                .then()
                .statusCode(201)
                .body("labels", hasSize(1))
                .body("labels[0].path", equalTo("legal/contracts"))
                .body("labels[0].persistence", equalTo("MANUAL"))
                .body("labels[0].appliedBy", equalTo("alice"));
    }

    @Test
    void createWorkItem_withInferredLabel_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Review contract",
                          "createdBy": "alice",
                          "labels": [
                            {"path": "legal/contracts", "persistence": "INFERRED", "appliedBy": null}
                          ]
                        }
                        """)
                .post("/workitems")
                .then()
                .statusCode(400);
    }

    @Test
    void createWorkItem_withNoLabels_returnsEmptyList() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "No labels",
                          "createdBy": "bob"
                        }
                        """)
                .post("/workitems")
                .then()
                .statusCode(201)
                .body("labels", hasSize(0));
    }

    @Test
    void createWorkItem_withMultipleLabels_allReturned() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Multi-label item",
                          "createdBy": "alice",
                          "labels": [
                            {"path": "legal/contracts", "persistence": "MANUAL", "appliedBy": "alice"},
                            {"path": "priority/high",   "persistence": "MANUAL", "appliedBy": "alice"}
                          ]
                        }
                        """)
                .post("/workitems")
                .then()
                .statusCode(201)
                .body("labels", hasSize(2));
    }

    @Test
    void vocabulary_listAll_includesSeededGlobalTerms() {
        given()
                .get("/vocabulary")
                .then()
                .statusCode(200)
                .body("path", org.hamcrest.Matchers.hasItem("legal/contracts"))
                .body("path", org.hamcrest.Matchers.hasItem("intake"));
    }

    @Test
    void vocabulary_addDefinition_appearsInList() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"path": "test/unique-vocab-54", "description": "test label", "addedBy": "alice"}
                        """)
                .post("/vocabulary/GLOBAL")
                .then()
                .statusCode(201)
                .body("path", equalTo("test/unique-vocab-54"));

        given()
                .get("/vocabulary")
                .then()
                .statusCode(200)
                .body("path", org.hamcrest.Matchers.hasItem("test/unique-vocab-54"));
    }

    @Test
    void vocabulary_addDefinition_invalidScope_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"x/y\", \"addedBy\": \"alice\"}")
                .post("/vocabulary/INVALID")
                .then()
                .statusCode(400);
    }

    @Test
    void vocabulary_addDefinition_personal_defaultsOwnerToAddedBy() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"my/personal/label\", \"addedBy\": \"alice\"}")
                .post("/vocabulary/PERSONAL")
                .then()
                .statusCode(201)
                .body("path", equalTo("my/personal/label"))
                .body("scope", equalTo("PERSONAL"));
    }

    @Test
    void vocabulary_addDefinition_org_withOwnerId_succeeds() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"org/finance/approvals\", \"addedBy\": \"alice\", \"ownerId\": \"acme-corp\"}")
                .post("/vocabulary/ORG")
                .then()
                .statusCode(201)
                .body("path", equalTo("org/finance/approvals"))
                .body("scope", equalTo("ORG"));
    }

    @Test
    void vocabulary_addDefinition_team_withOwnerId_succeeds() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"team/sprint/review\", \"addedBy\": \"bob\", \"ownerId\": \"team-alpha\"}")
                .post("/vocabulary/TEAM")
                .then()
                .statusCode(201)
                .body("path", equalTo("team/sprint/review"))
                .body("scope", equalTo("TEAM"));
    }

    @Test
    void vocabulary_addDefinition_org_missingOwnerId_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"org/some/label\", \"addedBy\": \"alice\"}")
                .post("/vocabulary/ORG")
                .then()
                .statusCode(400)
                .body("error", org.hamcrest.Matchers.containsString("ownerId"));
    }

    @Test
    void vocabulary_addDefinition_scopedTerm_appearsInListAll() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"my/team/label\", \"addedBy\": \"charlie\", \"ownerId\": \"team-bravo\"}")
                .post("/vocabulary/TEAM")
                .then()
                .statusCode(201);

        given()
                .get("/vocabulary")
                .then()
                .statusCode(200)
                .body("path", org.hamcrest.Matchers.hasItem("my/team/label"));
    }

    @Test
    void vocabulary_addDefinition_sameOwner_reuseVocabulary() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"personal/label/one\", \"addedBy\": \"dave\"}")
                .post("/vocabulary/PERSONAL")
                .then()
                .statusCode(201);

        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"personal/label/two\", \"addedBy\": \"dave\"}")
                .post("/vocabulary/PERSONAL")
                .then()
                .statusCode(201);

        given()
                .get("/vocabulary")
                .then()
                .statusCode(200)
                .body("path", org.hamcrest.Matchers.hasItem("personal/label/one"))
                .body("path", org.hamcrest.Matchers.hasItem("personal/label/two"));
    }

    @Test
    void vocabulary_addDefinition_emptyPath_returns400() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"\", \"addedBy\": \"alice\"}")
                .post("/vocabulary/GLOBAL")
                .then()
                .statusCode(400);
    }

    @Test
    void getWorkItems_byLabelPattern_returnsMatching() {
        given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Label query test 55",
                          "createdBy": "alice",
                          "labels": [{"path": "legal/contracts", "persistence": "MANUAL", "appliedBy": "alice"}]
                        }
                        """)
                .post("/workitems")
                .then().statusCode(201);

        given()
                .queryParam("label", "legal/contracts")
                .get("/workitems")
                .then()
                .statusCode(200)
                .body("title", org.hamcrest.Matchers.hasItem("Label query test 55"));

        given()
                .queryParam("label", "legal/**")
                .get("/workitems")
                .then()
                .statusCode(200)
                .body("title", org.hamcrest.Matchers.hasItem("Label query test 55"));
    }

    @Test
    void addManualLabel_toExistingWorkItem_appearsInResponse() {
        var id = given()
                .contentType(ContentType.JSON)
                .body("{\"title\": \"Add label test 55\", \"createdBy\": \"alice\"}")
                .post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"path\": \"legal/contracts\", \"appliedBy\": \"alice\"}")
                .post("/workitems/" + id + "/labels")
                .then()
                .statusCode(200)
                .body("labels.path", org.hamcrest.Matchers.hasItem("legal/contracts"));
    }

    @Test
    void removeManualLabel_fromWorkItem_disappearsFromResponse() {
        var id = given()
                .contentType(ContentType.JSON)
                .body("""
                        {
                          "title": "Remove label test 55",
                          "createdBy": "alice",
                          "labels": [{"path": "legal/contracts", "persistence": "MANUAL", "appliedBy": "alice"}]
                        }
                        """)
                .post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .queryParam("path", "legal/contracts")
                .delete("/workitems/" + id + "/labels")
                .then()
                .statusCode(200)
                .body("labels", hasSize(0));
    }

    @Test
    void removeNonExistentLabel_returns404() {
        var id = given()
                .contentType(ContentType.JSON)
                .body("{\"title\": \"404 label test\", \"createdBy\": \"alice\"}")
                .post("/workitems")
                .then().statusCode(201)
                .extract().path("id");

        given()
                .queryParam("path", "nonexistent/label")
                .delete("/workitems/" + id + "/labels")
                .then()
                .statusCode(404);
    }
}
