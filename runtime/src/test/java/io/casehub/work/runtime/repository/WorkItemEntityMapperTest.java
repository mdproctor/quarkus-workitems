package io.casehub.work.runtime.repository;

import io.casehub.work.api.*;
import io.casehub.work.runtime.model.WorkItemEntity;
import io.casehub.work.runtime.model.WorkItemLabelEntity;
import io.casehub.work.runtime.model.WorkItemType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WorkItemEntityMapperTest {

    @Test
    void toDomainMapsAllFields() {
        WorkItemEntity entity = new WorkItemEntity();
        entity.id = UUID.randomUUID();
        entity.tenancyId = "t1";
        entity.title = "Test";
        entity.description = "Desc";
        entity.formKey = "fk";
        entity.status = WorkItemStatus.PENDING;
        entity.priority = WorkItemPriority.HIGH;
        entity.assigneeId = "alice";
        entity.owner = "bob";
        entity.candidateGroups = "g1,g2";
        entity.candidateUsers = "u1";
        entity.requiredCapabilities = "rc";
        entity.createdBy = "system";
        entity.delegationChain = "dc";
        entity.delegationDeclineTarget = DeclineTarget.POOL;
        entity.priorStatus = WorkItemStatus.ASSIGNED;
        entity.payload = "{\"key\":\"val\"}";
        entity.resolution = "approved";
        Instant now = Instant.now();
        entity.claimDeadline = now;
        entity.expiresAt = now;
        entity.followUpDate = now;
        entity.createdAt = now;
        entity.updatedAt = now;
        entity.assignedAt = now;
        entity.startedAt = now;
        entity.completedAt = now;
        entity.suspendedAt = now;
        entity.accumulatedUnclaimedSeconds = 120L;
        entity.lastReturnedToPoolAt = now;
        entity.labels.add(new WorkItemLabelEntity("legal", LabelPersistence.MANUAL, "alice"));
        entity.types.add(new WorkItemType("compliance"));
        entity.confidenceScore = 0.85;
        entity.callerRef = "case:1/pi:2";
        entity.parentId = UUID.randomUUID();
        entity.scope = "org/team";
        entity.templateId = UUID.randomUUID();
        entity.templateVersion = 3L;
        entity.permittedOutcomes = "[\"yes\",\"no\"]";
        entity.excludedUsers = "charlie";
        entity.outcome = "yes";
        entity.inputDataSchema = "{}";
        entity.outputDataSchema = "{}";
        entity.payloadTypeName = "ptn";
        entity.resolutionTypeName = "rtn";
        entity.candidateScores = "{\"alice\":0.9}";
        entity.routingExperiences = "[]";

        WorkItem domain = WorkItemEntityMapper.toDomain(entity);

        assertEquals(entity.id, domain.id());
        assertEquals("t1", domain.tenancyId());
        assertEquals("Test", domain.title());
        assertEquals("Desc", domain.description());
        assertEquals("fk", domain.formKey());
        assertEquals(WorkItemStatus.PENDING, domain.status());
        assertEquals(WorkItemPriority.HIGH, domain.priority());
        assertEquals("alice", domain.assigneeId());
        assertEquals("bob", domain.owner());
        assertEquals("g1,g2", domain.candidateGroups());
        assertEquals("u1", domain.candidateUsers());
        assertEquals("rc", domain.requiredCapabilities());
        assertEquals("system", domain.createdBy());
        assertEquals("dc", domain.delegationChain());
        assertEquals(DeclineTarget.POOL, domain.delegationDeclineTarget());
        assertEquals(WorkItemStatus.ASSIGNED, domain.priorStatus());
        assertEquals("{\"key\":\"val\"}", domain.payload());
        assertEquals("approved", domain.resolution());
        assertEquals(now, domain.claimDeadline());
        assertEquals(now, domain.expiresAt());
        assertEquals(now, domain.followUpDate());
        assertEquals(now, domain.createdAt());
        assertEquals(now, domain.updatedAt());
        assertEquals(now, domain.assignedAt());
        assertEquals(now, domain.startedAt());
        assertEquals(now, domain.completedAt());
        assertEquals(now, domain.suspendedAt());
        assertEquals(120L, domain.accumulatedUnclaimedSeconds());
        assertEquals(now, domain.lastReturnedToPoolAt());
        assertEquals(1, domain.labels().size());
        assertEquals("legal", domain.labels().get(0).path());
        assertEquals(LabelPersistence.MANUAL, domain.labels().get(0).persistence());
        assertEquals(Set.of("compliance"), domain.types());
        assertEquals(0.85, domain.confidenceScore());
        assertEquals("case:1/pi:2", domain.callerRef());
        assertEquals(entity.parentId, domain.parentId());
        assertEquals("org/team", domain.scope());
        assertEquals(entity.templateId, domain.templateId());
        assertEquals(3L, domain.templateVersion());
        assertEquals("[\"yes\",\"no\"]", domain.permittedOutcomes());
        assertEquals("charlie", domain.excludedUsers());
        assertEquals("yes", domain.outcome());
        assertEquals("{}", domain.inputDataSchema());
        assertEquals("{}", domain.outputDataSchema());
        assertEquals("ptn", domain.payloadTypeName());
        assertEquals("rtn", domain.resolutionTypeName());
        assertEquals("{\"alice\":0.9}", domain.candidateScores());
        assertEquals("[]", domain.routingExperiences());
    }

    @Test
    void toEntityMapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        Instant now = Instant.now();
        WorkItem domain = WorkItem.builder()
                .id(id).tenancyId("t1").title("Test").description("D").formKey("fk")
                .status(WorkItemStatus.ASSIGNED).priority(WorkItemPriority.LOW)
                .assigneeId("a").owner("o").candidateGroups("g").candidateUsers("u")
                .requiredCapabilities("rc").createdBy("cb").delegationChain("dc")
                .delegationDeclineTarget(DeclineTarget.DELEGATOR)
                .priorStatus(WorkItemStatus.PENDING)
                .payload("p").resolution("r").claimDeadline(now).expiresAt(now)
                .followUpDate(now).createdAt(now).updatedAt(now).assignedAt(now)
                .startedAt(now).completedAt(now).suspendedAt(now)
                .accumulatedUnclaimedSeconds(60L).lastReturnedToPoolAt(now)
                .labels(List.of(new WorkItemLabel("finance", LabelPersistence.INFERRED, "rule-1")))
                .types(Set.of("audit")).confidenceScore(0.7).callerRef("cr")
                .parentId(parentId).scope("s").templateId(templateId).templateVersion(2L)
                .permittedOutcomes("po").excludedUsers("eu").outcome("oc")
                .inputDataSchema("ids").outputDataSchema("ods")
                .payloadTypeName("ptn").resolutionTypeName("rtn")
                .candidateScores("cs").routingExperiences("re")
                .build();

        WorkItemEntity entity = WorkItemEntityMapper.toEntity(domain);

        assertEquals(id, entity.id);
        assertEquals("t1", entity.tenancyId);
        assertEquals("Test", entity.title);
        assertEquals(WorkItemStatus.ASSIGNED, entity.status);
        assertEquals(1, entity.labels.size());
        assertEquals("finance", entity.labels.get(0).path);
        assertEquals(1, entity.types.size());
        assertEquals(60L, entity.accumulatedUnclaimedSeconds);
        assertEquals("cr", entity.callerRef);
        assertEquals(parentId, entity.parentId);
        assertEquals(templateId, entity.templateId);
        assertEquals(2L, entity.templateVersion);
    }

    @Test
    void updateEntityPreservesVersion() {
        WorkItemEntity entity = new WorkItemEntity();
        entity.id = UUID.randomUUID();
        entity.version = 5L;
        entity.tenancyId = "t1";
        entity.title = "Old";
        entity.status = WorkItemStatus.PENDING;

        WorkItem domain = WorkItem.builder()
                .id(entity.id)
                .tenancyId("t1")
                .title("New")
                .status(WorkItemStatus.ASSIGNED)
                .assigneeId("bob")
                .build();

        WorkItemEntityMapper.updateEntity(entity, domain);

        assertEquals(5L, entity.version);
        assertEquals("New", entity.title);
        assertEquals(WorkItemStatus.ASSIGNED, entity.status);
        assertEquals("bob", entity.assigneeId);
    }

    @Test
    void updateEntityOverwritesPopulatedFieldsWithNull() {
        WorkItemEntity entity = new WorkItemEntity();
        entity.id = UUID.randomUUID();
        entity.version = 3L;
        entity.tenancyId = "t1";
        entity.title = "Title";
        entity.followUpDate = Instant.parse("2026-06-01T00:00:00Z");
        entity.assigneeId = "alice";
        entity.scope = "org/team";

        WorkItem domain = WorkItem.builder()
                .id(entity.id)
                .tenancyId("t1")
                .title("Title")
                .build();

        WorkItemEntityMapper.updateEntity(entity, domain);

        assertNull(entity.followUpDate);
        assertNull(entity.assigneeId);
        assertNull(entity.scope);
        assertEquals(3L, entity.version);
    }

    @Test
    void roundTripPreservesAllFields() {
        UUID id = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        Instant now = Instant.now();
        WorkItem original = WorkItem.builder()
                .id(id).tenancyId("t1").title("T").description("D").formKey("fk")
                .status(WorkItemStatus.IN_PROGRESS).priority(WorkItemPriority.URGENT)
                .assigneeId("a").owner("o").candidateGroups("g").candidateUsers("u")
                .requiredCapabilities("rc").createdBy("cb").delegationChain("dc")
                .delegationDeclineTarget(DeclineTarget.POOL).priorStatus(WorkItemStatus.ASSIGNED)
                .payload("p").resolution("r").claimDeadline(now).expiresAt(now)
                .followUpDate(now).createdAt(now).updatedAt(now).assignedAt(now)
                .startedAt(now).completedAt(now).suspendedAt(now)
                .accumulatedUnclaimedSeconds(99L).lastReturnedToPoolAt(now)
                .labels(List.of(new WorkItemLabel("x", LabelPersistence.MANUAL, "y")))
                .types(Set.of("t")).confidenceScore(0.9).callerRef("cr")
                .parentId(parentId).scope("s").templateId(templateId)
                .templateVersion(2L).permittedOutcomes("po").excludedUsers("eu")
                .outcome("oc").inputDataSchema("ids").outputDataSchema("ods")
                .payloadTypeName("ptn").resolutionTypeName("rtn")
                .candidateScores("cs").routingExperiences("re")
                .version(0L)
                .compensationStatus(CompensationStatus.NONE)
                .build();

        WorkItemEntity entity = WorkItemEntityMapper.toEntity(original);
        WorkItem roundTripped = WorkItemEntityMapper.toDomain(entity);

        assertEquals(original, roundTripped);
    }
}
