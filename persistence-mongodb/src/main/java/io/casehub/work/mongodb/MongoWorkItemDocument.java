package io.casehub.work.mongodb;

import io.casehub.work.api.CompensationStatus;
import io.casehub.work.api.DeclineTarget;
import io.casehub.work.api.LabelPersistence;
import io.casehub.work.api.WorkItemPriority;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.model.WorkItemEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import io.quarkus.mongodb.panache.common.MongoEntity;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MongoDB document representation of a {@link WorkItemEntity}.
 *
 * <p>
 * Stored in the {@code work_items} collection. Converted to and from the domain
 * {@link WorkItemEntity} by {@link MongoWorkItemStore}.
 *
 * <p>
 * Unlike the JPA entity, {@code candidateGroups} and {@code candidateUsers} are stored
 * as arrays (not comma-separated strings), enabling efficient {@code $in} queries.
 */
@MongoEntity(collection = "work_items")
public class MongoWorkItemDocument extends PanacheMongoEntityBase {

    @BsonId
    public String id;

    public String tenancyId;
    public String title;
    public String description;
    public List<String> types = new ArrayList<>();
    public String formKey;
    public String status;
    public String priority;
    public String assigneeId;
    public String owner;
    public List<String> candidateGroups = new ArrayList<>();
    public List<String> candidateUsers = new ArrayList<>();
    public String requiredCapabilities;
    public String createdBy;
    public String delegationDeclineTarget;
    public String delegationChain;
    public String priorStatus;
    public String payload;
    public String resolution;
    public Instant claimDeadline;
    public Instant expiresAt;
    public Instant followUpDate;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant assignedAt;
    public Instant startedAt;
    public Instant completedAt;
    public Instant suspendedAt;
    public List<MongoLabel> labels = new ArrayList<>();
    public Long version;
    public long accumulatedUnclaimedSeconds;
    public Instant lastReturnedToPoolAt;
    public Double confidenceScore;
    public String callerRef;
    public String parentId;
    public String scope;
    public String templateId;
    public Long templateVersion;
    public String permittedOutcomes;
    public List<String> excludedUsers = new ArrayList<>();
    public String outcome;
    public String inputDataSchema;
    public String outputDataSchema;
    public String originServiceId;
    public String originWorkItemId;
    public Long   originVersion;
    public String compensationStatus;
    public String compensatesWorkItemId;


    /** Embedded label document. */
    public static class MongoLabel {
        public String path;
        public String persistence;
        public String appliedBy;
    }

    public static MongoWorkItemDocument from(final io.casehub.work.api.WorkItem wi) {
        final MongoWorkItemDocument doc = new MongoWorkItemDocument();
        doc.id                          = wi.id() != null ? wi.id().toString() : UUID.randomUUID().toString();
        doc.tenancyId                   = wi.tenancyId();
        doc.title                       = wi.title();
        doc.description                 = wi.description();
        doc.types                       = wi.types() != null ? List.copyOf(wi.types()) : List.of();
        doc.formKey                     = wi.formKey();
        doc.status                      = wi.status() != null ? wi.status().name() : null;
        doc.priority                    = wi.priority() != null ? wi.priority().name() : null;
        doc.assigneeId                  = wi.assigneeId();
        doc.owner                       = wi.owner();
        doc.candidateGroups             = splitCsv(wi.candidateGroups());
        doc.candidateUsers              = splitCsv(wi.candidateUsers());
        doc.requiredCapabilities        = wi.requiredCapabilities();
        doc.createdBy                   = wi.createdBy();
        doc.delegationDeclineTarget     = wi.delegationDeclineTarget() != null ? wi.delegationDeclineTarget().name() : null;
        doc.delegationChain             = wi.delegationChain();
        doc.priorStatus                 = wi.priorStatus() != null ? wi.priorStatus().name() : null;
        doc.payload                     = wi.payload();
        doc.resolution                  = wi.resolution();
        doc.claimDeadline               = wi.claimDeadline();
        doc.expiresAt                   = wi.expiresAt();
        doc.followUpDate                = wi.followUpDate();
        doc.createdAt                   = wi.createdAt();
        doc.updatedAt                   = wi.updatedAt();
        doc.assignedAt                  = wi.assignedAt();
        doc.startedAt                   = wi.startedAt();
        doc.completedAt                 = wi.completedAt();
        doc.suspendedAt                 = wi.suspendedAt();
        doc.accumulatedUnclaimedSeconds = wi.accumulatedUnclaimedSeconds();
        doc.lastReturnedToPoolAt        = wi.lastReturnedToPoolAt();
        doc.confidenceScore             = wi.confidenceScore();
        doc.callerRef                   = wi.callerRef();
        doc.parentId                    = wi.parentId() != null ? wi.parentId().toString() : null;
        doc.scope                       = wi.scope();
        doc.templateId                  = wi.templateId() != null ? wi.templateId().toString() : null;
        doc.templateVersion             = wi.templateVersion();
        doc.permittedOutcomes           = wi.permittedOutcomes();
        doc.excludedUsers               = splitCsv(wi.excludedUsers());
        doc.outcome                     = wi.outcome();
        doc.inputDataSchema             = wi.inputDataSchema();
        doc.outputDataSchema            = wi.outputDataSchema();
        doc.originServiceId             = wi.originServiceId();
        doc.originWorkItemId            = wi.originWorkItemId() != null ? wi.originWorkItemId().toString() : null;
        doc.originVersion               = wi.originVersion();
        doc.compensationStatus          = wi.compensationStatus() != null ? wi.compensationStatus().name() : null;
        doc.compensatesWorkItemId       = wi.compensatesWorkItemId() != null ? wi.compensatesWorkItemId().toString() : null;
        if (wi.labels() != null) {
            doc.labels = wi.labels().stream().map(l -> {
                final MongoLabel ml = new MongoLabel();
                ml.path        = l.path();
                ml.persistence = l.persistence() != null ? l.persistence().name() : null;
                ml.appliedBy   = l.appliedBy();
                return ml;
            }).collect(Collectors.toList());
        }
        return doc;
    }

    public io.casehub.work.api.WorkItem toDomain() {
        return io.casehub.work.api.WorkItem.builder()
                                           .id(UUID.fromString(id))
                                           .tenancyId(tenancyId)
                                           .title(title)
                                           .description(description)
                                           .types(types != null ? new java.util.LinkedHashSet<>(types) : java.util.Set.of())
                                           .formKey(formKey)
                                           .status(status != null ? WorkItemStatus.valueOf(status) : null)
                                           .priority(priority != null ? WorkItemPriority.valueOf(priority) : null)
                                           .assigneeId(assigneeId)
                                           .owner(owner)
                                           .candidateGroups(joinCsv(candidateGroups))
                                           .candidateUsers(joinCsv(candidateUsers))
                                           .requiredCapabilities(requiredCapabilities)
                                           .createdBy(createdBy)
                                           .delegationDeclineTarget(delegationDeclineTarget != null ? DeclineTarget.valueOf(delegationDeclineTarget) : null)
                                           .delegationChain(delegationChain)
                                           .priorStatus(priorStatus != null ? WorkItemStatus.valueOf(priorStatus) : null)
                                           .payload(payload)
                                           .resolution(resolution)
                                           .claimDeadline(claimDeadline)
                                           .expiresAt(expiresAt)
                                           .followUpDate(followUpDate)
                                           .createdAt(createdAt)
                                           .updatedAt(updatedAt)
                                           .assignedAt(assignedAt)
                                           .startedAt(startedAt)
                                           .completedAt(completedAt)
                                           .suspendedAt(suspendedAt)
                                           .accumulatedUnclaimedSeconds(accumulatedUnclaimedSeconds)
                                           .lastReturnedToPoolAt(lastReturnedToPoolAt)
                                           .confidenceScore(confidenceScore)
                                           .callerRef(callerRef)
                                           .parentId(parentId != null ? UUID.fromString(parentId) : null)
                                           .scope(scope)
                                           .templateId(templateId != null ? UUID.fromString(templateId) : null)
                                           .templateVersion(templateVersion)
                                           .permittedOutcomes(permittedOutcomes)
                                           .excludedUsers(joinCsv(excludedUsers))
                                           .outcome(outcome)
                                           .inputDataSchema(inputDataSchema)
                                           .outputDataSchema(outputDataSchema)
                                           .version(version)
                                           .originServiceId(originServiceId)
                                           .originWorkItemId(originWorkItemId != null ? UUID.fromString(originWorkItemId) : null)
                                           .originVersion(originVersion)
                                           .compensationStatus(compensationStatus != null ? CompensationStatus.valueOf(compensationStatus) : CompensationStatus.NONE)
                                           .compensatesWorkItemId(compensatesWorkItemId != null ? UUID.fromString(compensatesWorkItemId) : null)
                                           .labels(labels != null ? labels.stream().map(ml ->
                                                                                                new io.casehub.work.api.WorkItemLabel(ml.path,
                                                                                                                                      ml.persistence != null ? LabelPersistence.valueOf(ml.persistence) : null,
                                                                                                                                      ml.appliedBy)).toList() : List.of())
                                           .build();
    }

    private static List<String> splitCsv(final String csv) {
        if (csv == null || csv.isBlank()) {
            return new ArrayList<>();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static String joinCsv(final List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return String.join(",", list);
    }
}
