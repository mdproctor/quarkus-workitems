package io.casehub.work.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;


public final class WorkItemCreateRequest {

    public final String title;
    public final String description;
    public final List<String> types;
    public final String                               formKey;
    public final io.casehub.work.api.WorkItemPriority priority;
    public final String                               assigneeId;
    public final String candidateGroups;
    public final String candidateUsers;
    public final String requiredCapabilities;
    public final String createdBy;
    public final String payload;
    public final Instant claimDeadline;
    public final Instant expiresAt;
    public final Instant                                        followUpDate;
    public final List<io.casehub.work.api.WorkItemLabelRequest> labels;
    public final Double                                         confidenceScore;
    public final String callerRef;
    public final Integer claimDeadlineBusinessHours;
    public final Integer expiresAtBusinessHours;
    public final UUID templateId;
    public final List<Outcome> permittedOutcomes;
    public final String inputDataSchema;
    public final String outputDataSchema;
    public final String excludedUsers;

    /** Hierarchical scope path e.g. {@code "casehubio/devtown/pr-review"}; null means root scope. */
    public final String scope;

    /** Optional detail appended to the CREATED audit entry. Used to record group expansion notes. */
    public final String auditDetail;

    /** Version of the template used at instantiation; null for non-template WorkItems. */
    public final Long templateVersion;

    /** Tenant identifier for multi-tenant SPI callers. */
    public final String tenancyId;

    public final String payloadTypeName;
    public final String resolutionTypeName;
    public final String candidateScores;
    public final String routingExperiences;
    public final String routingStrategy;
    public final Double minimumScore;
    public final String escalationOnExpiry;
    public final String escalationOnClaimDeadline;
    public final String escalationDeadline;
    public final Boolean escalationGenerateSummary;
    public final String originRef;


    private WorkItemCreateRequest(final Builder b) {
        this.title                      = b.title;
        this.description                = b.description;
        this.types                      = b.types;
        this.formKey                    = b.formKey;
        this.priority                   = b.priority;
        this.assigneeId                 = b.assigneeId;
        this.candidateGroups            = b.candidateGroups;
        this.candidateUsers             = b.candidateUsers;
        this.requiredCapabilities       = b.requiredCapabilities;
        this.createdBy                  = b.createdBy;
        this.payload                    = b.payload;
        this.claimDeadline              = b.claimDeadline;
        this.expiresAt                  = b.expiresAt;
        this.followUpDate               = b.followUpDate;
        this.labels                     = b.labels;
        this.confidenceScore            = b.confidenceScore;
        this.callerRef                  = b.callerRef;
        this.claimDeadlineBusinessHours = b.claimDeadlineBusinessHours;
        this.expiresAtBusinessHours     = b.expiresAtBusinessHours;
        this.templateId                 = b.templateId;
        this.permittedOutcomes          = b.permittedOutcomes;
        this.inputDataSchema            = b.inputDataSchema;
        this.outputDataSchema           = b.outputDataSchema;
        this.excludedUsers              = b.excludedUsers;
        this.scope                      = b.scope;
        this.auditDetail                = b.auditDetail;
        this.templateVersion            = b.templateVersion;
        this.tenancyId                  = b.tenancyId;
        this.payloadTypeName            = b.payloadTypeName;
        this.resolutionTypeName         = b.resolutionTypeName;
        this.candidateScores            = b.candidateScores;
        this.routingExperiences         = b.routingExperiences;
        this.routingStrategy            = b.routingStrategy;
        this.minimumScore               = b.minimumScore;
        this.escalationOnExpiry         = b.escalationOnExpiry;
        this.escalationOnClaimDeadline  = b.escalationOnClaimDeadline;
        this.escalationDeadline         = b.escalationDeadline;
        this.escalationGenerateSummary  = b.escalationGenerateSummary;
        this.originRef                  = b.originRef;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {return true;}
        if (!(o instanceof WorkItemCreateRequest r)) {return false;}
        return Objects.equals(title, r.title)
               && Objects.equals(description, r.description)
               && Objects.equals(types, r.types)
               && Objects.equals(formKey, r.formKey)
               && Objects.equals(priority, r.priority)
               && Objects.equals(assigneeId, r.assigneeId)
               && Objects.equals(candidateGroups, r.candidateGroups)
               && Objects.equals(candidateUsers, r.candidateUsers)
               && Objects.equals(requiredCapabilities, r.requiredCapabilities)
               && Objects.equals(createdBy, r.createdBy)
               && Objects.equals(payload, r.payload)
               && Objects.equals(claimDeadline, r.claimDeadline)
               && Objects.equals(expiresAt, r.expiresAt)
               && Objects.equals(followUpDate, r.followUpDate)
               && Objects.equals(labels, r.labels)
               && Objects.equals(confidenceScore, r.confidenceScore)
               && Objects.equals(callerRef, r.callerRef)
               && Objects.equals(claimDeadlineBusinessHours, r.claimDeadlineBusinessHours)
               && Objects.equals(expiresAtBusinessHours, r.expiresAtBusinessHours)
               && Objects.equals(templateId, r.templateId)
               && Objects.equals(permittedOutcomes, r.permittedOutcomes)
               && Objects.equals(inputDataSchema, r.inputDataSchema)
               && Objects.equals(outputDataSchema, r.outputDataSchema)
               && Objects.equals(excludedUsers, r.excludedUsers)
               && Objects.equals(scope, r.scope)
               && Objects.equals(auditDetail, r.auditDetail)
               && Objects.equals(templateVersion, r.templateVersion)
               && Objects.equals(tenancyId, r.tenancyId)
               && Objects.equals(payloadTypeName, r.payloadTypeName)
               && Objects.equals(resolutionTypeName, r.resolutionTypeName)
               && Objects.equals(candidateScores, r.candidateScores)
               && Objects.equals(routingExperiences, r.routingExperiences)
               && Objects.equals(routingStrategy, r.routingStrategy)
               && Objects.equals(minimumScore, r.minimumScore)
               && Objects.equals(escalationOnExpiry, r.escalationOnExpiry)
               && Objects.equals(escalationOnClaimDeadline, r.escalationOnClaimDeadline)
               && Objects.equals(escalationDeadline, r.escalationDeadline)
               && Objects.equals(escalationGenerateSummary, r.escalationGenerateSummary);
    }

    @Override
    public int hashCode() {
        return Objects.hash(title, description, types, formKey, priority,
                            assigneeId, candidateGroups, candidateUsers, requiredCapabilities,
                            createdBy, payload, claimDeadline, expiresAt, followUpDate, labels,
                            confidenceScore, callerRef, claimDeadlineBusinessHours, expiresAtBusinessHours,
                            templateId, permittedOutcomes, inputDataSchema, outputDataSchema, excludedUsers, scope,
                            auditDetail, templateVersion, tenancyId,
                            payloadTypeName, resolutionTypeName,
                            candidateScores, routingExperiences,
                            routingStrategy, minimumScore,
                            escalationOnExpiry, escalationOnClaimDeadline,
                            escalationDeadline, escalationGenerateSummary);
    }

    /** Intentionally omits payload, schemas, callerRef, and credentials — log-safety. */
    @Override
    public String toString() {
        return "WorkItemCreateRequest{title='" + title + "', types=" + types
                + ", priority=" + priority + ", assigneeId='" + assigneeId
                + "', candidateGroups='" + candidateGroups + "'}";
    }

    public static final class Builder {

        private String title;
        private String description;
        private List<String> types;
        private String                               formKey;
        private io.casehub.work.api.WorkItemPriority priority;
        private String                               assigneeId;
        private String candidateGroups;
        private String candidateUsers;
        private String requiredCapabilities;
        private String createdBy;
        private String payload;
        private Instant claimDeadline;
        private Instant expiresAt;
        private Instant                                        followUpDate;
        private List<io.casehub.work.api.WorkItemLabelRequest> labels;
        private Double                                         confidenceScore;
        private String callerRef;
        private Integer claimDeadlineBusinessHours;
        private Integer expiresAtBusinessHours;
        private UUID templateId;
        private List<Outcome> permittedOutcomes;
        private String inputDataSchema;
        private String outputDataSchema;
        private String excludedUsers;
        private String scope;
        private String auditDetail;
        private Long templateVersion;
        private String tenancyId;
        private String payloadTypeName;
        private String resolutionTypeName;
        private String candidateScores;
        private String routingExperiences;
        private String routingStrategy;
        private Double minimumScore;
        private String escalationOnExpiry;
        private String escalationOnClaimDeadline;
        private String escalationDeadline;
        private Boolean escalationGenerateSummary;
        private String originRef;


        private Builder() {}

        private Builder(final WorkItemCreateRequest src) {
            this.title                      = src.title;
            this.description                = src.description;
            this.types                      = src.types;
            this.formKey                    = src.formKey;
            this.priority                   = src.priority;
            this.assigneeId                 = src.assigneeId;
            this.candidateGroups            = src.candidateGroups;
            this.candidateUsers             = src.candidateUsers;
            this.requiredCapabilities       = src.requiredCapabilities;
            this.createdBy                  = src.createdBy;
            this.payload                    = src.payload;
            this.claimDeadline              = src.claimDeadline;
            this.expiresAt                  = src.expiresAt;
            this.followUpDate               = src.followUpDate;
            this.labels                     = src.labels;
            this.confidenceScore            = src.confidenceScore;
            this.callerRef                  = src.callerRef;
            this.claimDeadlineBusinessHours = src.claimDeadlineBusinessHours;
            this.expiresAtBusinessHours     = src.expiresAtBusinessHours;
            this.templateId                 = src.templateId;
            this.permittedOutcomes          = src.permittedOutcomes;
            this.inputDataSchema            = src.inputDataSchema;
            this.outputDataSchema           = src.outputDataSchema;
            this.excludedUsers              = src.excludedUsers;
            this.scope                      = src.scope;
            this.auditDetail                = src.auditDetail;
            this.templateVersion            = src.templateVersion;
            this.tenancyId                  = src.tenancyId;
            this.payloadTypeName            = src.payloadTypeName;
            this.resolutionTypeName         = src.resolutionTypeName;
            this.candidateScores            = src.candidateScores;
            this.routingExperiences         = src.routingExperiences;
            this.routingStrategy            = src.routingStrategy;
            this.minimumScore               = src.minimumScore;
            this.escalationOnExpiry         = src.escalationOnExpiry;
            this.escalationOnClaimDeadline  = src.escalationOnClaimDeadline;
            this.escalationDeadline         = src.escalationDeadline;
            this.escalationGenerateSummary  = src.escalationGenerateSummary;
            this.originRef                  = src.originRef;
        }

        public Builder title(final String v)                          { this.title = v; return this; }
        public Builder description(final String v)                    { this.description = v; return this; }
        public Builder types(final List<String> v)                    { this.types = v; return this; }
        public Builder formKey(final String v)                                { this.formKey = v; return this; }
        public Builder priority(final io.casehub.work.api.WorkItemPriority v) {this.priority = v; return this; }
        public Builder assigneeId(final String v)                             { this.assigneeId = v; return this; }
        public Builder candidateGroups(final String v)                { this.candidateGroups = v; return this; }
        public Builder candidateUsers(final String v)                 { this.candidateUsers = v; return this; }
        public Builder requiredCapabilities(final String v)           { this.requiredCapabilities = v; return this; }
        public Builder createdBy(final String v)                      { this.createdBy = v; return this; }
        public Builder payload(final String v)                        { this.payload = v; return this; }
        public Builder claimDeadline(final Instant v)                 { this.claimDeadline = v; return this; }
        public Builder expiresAt(final Instant v)                     { this.expiresAt = v; return this; }
        public Builder followUpDate(final Instant v)                                  { this.followUpDate = v; return this; }
        public Builder labels(final List<io.casehub.work.api.WorkItemLabelRequest> v) {this.labels = v; return this; }
        public Builder confidenceScore(final Double v)                                { this.confidenceScore = v; return this; }
        public Builder callerRef(final String v)                      { this.callerRef = v; return this; }
        public Builder claimDeadlineBusinessHours(final Integer v)    { this.claimDeadlineBusinessHours = v; return this; }
        public Builder expiresAtBusinessHours(final Integer v)        { this.expiresAtBusinessHours = v; return this; }
        public Builder templateId(final UUID v)                       { this.templateId = v; return this; }
        public Builder permittedOutcomes(final List<Outcome> v)        { this.permittedOutcomes = v; return this; }
        public Builder inputDataSchema(final String v)                { this.inputDataSchema = v; return this; }
        public Builder outputDataSchema(final String v)               { this.outputDataSchema = v; return this; }
        public Builder excludedUsers(final String v)                  { this.excludedUsers = v; return this; }
        public Builder scope(final String v)                          { this.scope = v; return this; }
        public Builder auditDetail(final String v)                    { this.auditDetail = v; return this; }
        public Builder templateVersion(final Long v)                 { this.templateVersion = v; return this; }
        public Builder tenancyId(final String v)                      { this.tenancyId = v; return this; }
        public Builder payloadTypeName(final String v)               { this.payloadTypeName = v; return this; }
        public Builder resolutionTypeName(final String v)            { this.resolutionTypeName = v; return this; }
        public Builder candidateScores(final String v)              { this.candidateScores = v; return this; }
        public Builder routingExperiences(final String v)           { this.routingExperiences = v; return this; }

        public Builder routingStrategy(final String v)              {
                                                                        this.routingStrategy = v;
                                                                        return this;
                                                                    }

        public Builder minimumScore(final Double v)                 {
                                                                        this.minimumScore = v;
                                                                        return this;
                                                                    }

        public Builder escalationOnExpiry(final String v)          { this.escalationOnExpiry = v; return this; }
        public Builder escalationOnClaimDeadline(final String v)   { this.escalationOnClaimDeadline = v; return this; }
        public Builder escalationDeadline(final String v)          { this.escalationDeadline = v; return this; }
        public Builder escalationGenerateSummary(final Boolean v)  { this.escalationGenerateSummary = v; return this; }
        public Builder originRef(final String v)                   { this.originRef = v; return this; }


        public WorkItemCreateRequest build() {
            if (templateId == null && (title == null || title.isBlank()))
                throw new IllegalArgumentException("title is required");
            return new WorkItemCreateRequest(this);
        }
    }
}
