package io.casehub.work.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record WorkItem(
        UUID id,
        String tenancyId,
        String title,
        String description,
        String formKey,
        WorkItemStatus status,
        WorkItemPriority priority,
        String assigneeId,
        String owner,
        String candidateGroups,
        String candidateUsers,
        String requiredCapabilities,
        String createdBy,
        String delegationChain,
        DeclineTarget delegationDeclineTarget,
        WorkItemStatus priorStatus,
        String payload,
        String resolution,
        Instant claimDeadline,
        Instant expiresAt,
        Instant followUpDate,
        Instant createdAt,
        Instant updatedAt,
        Instant assignedAt,
        Instant startedAt,
        Instant completedAt,
        Instant suspendedAt,
        long accumulatedUnclaimedSeconds,
        Instant lastReturnedToPoolAt,
        List<WorkItemLabel> labels,
        Set<String> types,
        Double confidenceScore,
        String callerRef,
        UUID parentId,
        String scope,
        UUID templateId,
        Long templateVersion,
        String permittedOutcomes,
        String excludedUsers,
        String outcome,
        String inputDataSchema,
        String outputDataSchema,
        String payloadTypeName,
        String resolutionTypeName,
        String candidateScores,
        String routingExperiences,
        Long version,
        String originServiceId,
        UUID originWorkItemId,
        Long originVersion,
        String escalationOnExpiry,
        String escalationOnClaimDeadline,
        String escalationDeadline,
        Boolean escalationGenerateSummary,
        CompensationStatus compensationStatus,
        UUID compensatesWorkItemId,
        String originRef
) {

    public WorkItem {
        labels = labels != null ? Collections.unmodifiableList(labels) : List.of();
        types  = types != null ? Collections.unmodifiableSet(new LinkedHashSet<>(types)) : Set.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                       .id(id).tenancyId(tenancyId).title(title).description(description)
                       .formKey(formKey).status(status).priority(priority)
                       .assigneeId(assigneeId).owner(owner)
                       .candidateGroups(candidateGroups).candidateUsers(candidateUsers)
                       .requiredCapabilities(requiredCapabilities).createdBy(createdBy)
                       .delegationChain(delegationChain).delegationDeclineTarget(delegationDeclineTarget)
                       .priorStatus(priorStatus).payload(payload).resolution(resolution)
                       .claimDeadline(claimDeadline).expiresAt(expiresAt).followUpDate(followUpDate)
                       .createdAt(createdAt).updatedAt(updatedAt).assignedAt(assignedAt)
                       .startedAt(startedAt).completedAt(completedAt).suspendedAt(suspendedAt)
                       .accumulatedUnclaimedSeconds(accumulatedUnclaimedSeconds)
                       .lastReturnedToPoolAt(lastReturnedToPoolAt)
                       .labels(labels).types(types).confidenceScore(confidenceScore)
                       .callerRef(callerRef).parentId(parentId).scope(scope)
                       .templateId(templateId).templateVersion(templateVersion)
                       .permittedOutcomes(permittedOutcomes).excludedUsers(excludedUsers)
                       .outcome(outcome).inputDataSchema(inputDataSchema)
                       .outputDataSchema(outputDataSchema).payloadTypeName(payloadTypeName)
                       .resolutionTypeName(resolutionTypeName)
                       .candidateScores(candidateScores).routingExperiences(routingExperiences)
                       .version(version)
                       .originServiceId(originServiceId).originWorkItemId(originWorkItemId)
                       .originVersion(originVersion)
                       .escalationOnExpiry(escalationOnExpiry)
                       .escalationOnClaimDeadline(escalationOnClaimDeadline)
                       .escalationDeadline(escalationDeadline)
                       .escalationGenerateSummary(escalationGenerateSummary)
                       .compensationStatus(compensationStatus)
                       .compensatesWorkItemId(compensatesWorkItemId)
                       .originRef(originRef);
    }

    public static final class Builder {
        private UUID                id;
        private String              tenancyId;
        private String              title;
        private String              description;
        private String              formKey;
        private WorkItemStatus      status;
        private WorkItemPriority    priority;
        private String              assigneeId;
        private String              owner;
        private String              candidateGroups;
        private String              candidateUsers;
        private String              requiredCapabilities;
        private String              createdBy;
        private String              delegationChain;
        private DeclineTarget       delegationDeclineTarget;
        private WorkItemStatus      priorStatus;
        private String              payload;
        private String              resolution;
        private Instant             claimDeadline;
        private Instant             expiresAt;
        private Instant             followUpDate;
        private Instant             createdAt;
        private Instant             updatedAt;
        private Instant             assignedAt;
        private Instant             startedAt;
        private Instant             completedAt;
        private Instant             suspendedAt;
        private long                accumulatedUnclaimedSeconds;
        private Instant             lastReturnedToPoolAt;
        private List<WorkItemLabel> labels = List.of();
        private Set<String>         types  = Set.of();
        private Double              confidenceScore;
        private String              callerRef;
        private UUID                parentId;
        private String              scope;
        private UUID                templateId;
        private Long                templateVersion;
        private String              permittedOutcomes;
        private String              excludedUsers;
        private String              outcome;
        private String              inputDataSchema;
        private String              outputDataSchema;
        private String              payloadTypeName;
        private String              resolutionTypeName;
        private String              candidateScores;
        private String              routingExperiences;
        private Long                version;
        private String              originServiceId;
        private UUID                originWorkItemId;
        private Long                originVersion;
        private String              escalationOnExpiry;
        private String              escalationOnClaimDeadline;
        private String              escalationDeadline;
        private Boolean             escalationGenerateSummary;
        private CompensationStatus  compensationStatus;
        private UUID                compensatesWorkItemId;
        private String              originRef;

        public Builder id(UUID v)                               {
                                                                    this.id = v;
                                                                    return this;
                                                                }

        public Builder tenancyId(String v)                      {
                                                                    this.tenancyId = v;
                                                                    return this;
                                                                }

        public Builder title(String v)                          {
                                                                    this.title = v;
                                                                    return this;
                                                                }

        public Builder description(String v)                    {
                                                                    this.description = v;
                                                                    return this;
                                                                }

        public Builder formKey(String v)                        {
                                                                    this.formKey = v;
                                                                    return this;
                                                                }

        public Builder status(WorkItemStatus v)                 {
                                                                    this.status = v;
                                                                    return this;
                                                                }

        public Builder priority(WorkItemPriority v)             {
                                                                    this.priority = v;
                                                                    return this;
                                                                }

        public Builder assigneeId(String v)                     {
                                                                    this.assigneeId = v;
                                                                    return this;
                                                                }

        public Builder owner(String v)                          {
                                                                    this.owner = v;
                                                                    return this;
                                                                }

        public Builder candidateGroups(String v)                {
                                                                    this.candidateGroups = v;
                                                                    return this;
                                                                }

        public Builder candidateUsers(String v)                 {
                                                                    this.candidateUsers = v;
                                                                    return this;
                                                                }

        public Builder requiredCapabilities(String v)           {
                                                                    this.requiredCapabilities = v;
                                                                    return this;
                                                                }

        public Builder createdBy(String v)                      {
                                                                    this.createdBy = v;
                                                                    return this;
                                                                }

        public Builder delegationChain(String v)                {
                                                                    this.delegationChain = v;
                                                                    return this;
                                                                }

        public Builder delegationDeclineTarget(DeclineTarget v) {
                                                                    this.delegationDeclineTarget = v;
                                                                    return this;
                                                                }

        public Builder priorStatus(WorkItemStatus v)            {
                                                                    this.priorStatus = v;
                                                                    return this;
                                                                }

        public Builder payload(String v)                        {
                                                                    this.payload = v;
                                                                    return this;
                                                                }

        public Builder resolution(String v)                     {
                                                                    this.resolution = v;
                                                                    return this;
                                                                }

        public Builder claimDeadline(Instant v)                 {
                                                                    this.claimDeadline = v;
                                                                    return this;
                                                                }

        public Builder expiresAt(Instant v)                     {
                                                                    this.expiresAt = v;
                                                                    return this;
                                                                }

        public Builder followUpDate(Instant v)                  {
                                                                    this.followUpDate = v;
                                                                    return this;
                                                                }

        public Builder createdAt(Instant v)                     {
                                                                    this.createdAt = v;
                                                                    return this;
                                                                }

        public Builder updatedAt(Instant v)                     {
                                                                    this.updatedAt = v;
                                                                    return this;
                                                                }

        public Builder assignedAt(Instant v)                    {
                                                                    this.assignedAt = v;
                                                                    return this;
                                                                }

        public Builder startedAt(Instant v)                     {
                                                                    this.startedAt = v;
                                                                    return this;
                                                                }

        public Builder completedAt(Instant v)                   {
                                                                    this.completedAt = v;
                                                                    return this;
                                                                }

        public Builder suspendedAt(Instant v)                   {
                                                                    this.suspendedAt = v;
                                                                    return this;
                                                                }

        public Builder accumulatedUnclaimedSeconds(long v)      {
                                                                    this.accumulatedUnclaimedSeconds = v;
                                                                    return this;
                                                                }

        public Builder lastReturnedToPoolAt(Instant v)          {
                                                                    this.lastReturnedToPoolAt = v;
                                                                    return this;
                                                                }

        public Builder labels(List<WorkItemLabel> v)            {
                                                                    this.labels = v;
                                                                    return this;
                                                                }

        public Builder types(Set<String> v)                     {
                                                                    this.types = v;
                                                                    return this;
                                                                }

        public Builder confidenceScore(Double v)                {
                                                                    this.confidenceScore = v;
                                                                    return this;
                                                                }

        public Builder callerRef(String v)                      {
                                                                    this.callerRef = v;
                                                                    return this;
                                                                }

        public Builder parentId(UUID v)                         {
                                                                    this.parentId = v;
                                                                    return this;
                                                                }

        public Builder scope(String v)                          {
                                                                    this.scope = v;
                                                                    return this;
                                                                }

        public Builder templateId(UUID v)                       {
                                                                    this.templateId = v;
                                                                    return this;
                                                                }

        public Builder templateVersion(Long v)                  {
                                                                    this.templateVersion = v;
                                                                    return this;
                                                                }

        public Builder permittedOutcomes(String v)              {
                                                                    this.permittedOutcomes = v;
                                                                    return this;
                                                                }

        public Builder excludedUsers(String v)                  {
                                                                    this.excludedUsers = v;
                                                                    return this;
                                                                }

        public Builder outcome(String v)                        {
                                                                    this.outcome = v;
                                                                    return this;
                                                                }

        public Builder inputDataSchema(String v)                {
                                                                    this.inputDataSchema = v;
                                                                    return this;
                                                                }

        public Builder outputDataSchema(String v)               {
                                                                    this.outputDataSchema = v;
                                                                    return this;
                                                                }

        public Builder payloadTypeName(String v)                {
                                                                    this.payloadTypeName = v;
                                                                    return this;
                                                                }

        public Builder resolutionTypeName(String v)             {
                                                                    this.resolutionTypeName = v;
                                                                    return this;
                                                                }

        public Builder candidateScores(String v)                {
                                                                    this.candidateScores = v;
                                                                    return this;
                                                                }

        public Builder routingExperiences(String v)             {
                                                                    this.routingExperiences = v;
                                                                    return this;
                                                                }

        public Builder version(Long v)                          {
                                                                    this.version = v;
                                                                    return this;
                                                                }

        public Builder originServiceId(String v)                {
                                                                    this.originServiceId = v;
                                                                    return this;
                                                                }

        public Builder originWorkItemId(UUID v)                 {
                                                                    this.originWorkItemId = v;
                                                                    return this;
                                                                }

        public Builder originVersion(Long v)                    {
                                                                    this.originVersion = v;
                                                                    return this;
                                                                }

        public Builder escalationOnExpiry(String v)             { this.escalationOnExpiry = v; return this; }
        public Builder escalationOnClaimDeadline(String v)      { this.escalationOnClaimDeadline = v; return this; }
        public Builder escalationDeadline(String v)             { this.escalationDeadline = v; return this; }
        public Builder escalationGenerateSummary(Boolean v)     { this.escalationGenerateSummary = v; return this; }
        public Builder compensationStatus(CompensationStatus v) { this.compensationStatus = v; return this; }
        public Builder compensatesWorkItemId(UUID v)          { this.compensatesWorkItemId = v; return this; }
        public Builder originRef(String v)                    { this.originRef = v; return this; }

        public WorkItem build() {
            return new WorkItem(
                    id, tenancyId, title, description, formKey, status, priority,
                    assigneeId, owner, candidateGroups, candidateUsers,
                    requiredCapabilities, createdBy, delegationChain,
                    delegationDeclineTarget, priorStatus, payload, resolution,
                    claimDeadline, expiresAt, followUpDate, createdAt, updatedAt,
                    assignedAt, startedAt, completedAt, suspendedAt,
                    accumulatedUnclaimedSeconds, lastReturnedToPoolAt,
                    labels, types, confidenceScore, callerRef, parentId, scope,
                    templateId, templateVersion, permittedOutcomes, excludedUsers,
                    outcome, inputDataSchema, outputDataSchema,
                    payloadTypeName, resolutionTypeName,
                    candidateScores, routingExperiences, version,
                    originServiceId, originWorkItemId, originVersion,
                    escalationOnExpiry, escalationOnClaimDeadline,
                    escalationDeadline, escalationGenerateSummary,
                    compensationStatus, compensatesWorkItemId, originRef);
        }
    }
}
