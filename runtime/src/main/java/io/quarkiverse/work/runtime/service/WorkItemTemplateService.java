package io.quarkiverse.work.runtime.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkiverse.work.runtime.model.LabelPersistence;
import io.quarkiverse.work.runtime.model.WorkItem;
import io.quarkiverse.work.runtime.model.WorkItemCreateRequest;
import io.quarkiverse.work.runtime.model.WorkItemLabel;
import io.quarkiverse.work.runtime.model.WorkItemTemplate;

/**
 * Service for creating and instantiating {@link WorkItemTemplate} records.
 *
 * <p>
 * The unit-testable static methods ({@link #toCreateRequest} and {@link #parseLabels})
 * contain the pure mapping logic with no CDI or JPA dependencies. The CDI methods
 * ({@link #instantiate}) delegate to these statics for easy testing.
 */
@ApplicationScoped
public class WorkItemTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    WorkItemService workItemService;

    /**
     * Instantiate a {@link WorkItemTemplate} into a new PENDING {@link WorkItem}.
     *
     * <p>
     * The WorkItem is created with the template's defaults. The caller may supply:
     * <ul>
     * <li>{@code titleOverride} — if null, {@link WorkItemTemplate#name} is used as the title</li>
     * <li>{@code assigneeIdOverride} — if non-null, the WorkItem is pre-assigned</li>
     * <li>{@code createdBy} — who or what triggered the instantiation</li>
     * </ul>
     *
     * <p>
     * After the WorkItem is created, any labels from {@link WorkItemTemplate#labelPaths}
     * are applied as MANUAL labels. This happens inside the same transaction.
     *
     * @param template the template to instantiate; must not be null
     * @param titleOverride optional title; defaults to template name
     * @param assigneeIdOverride optional direct assignee; overrides candidateGroups routing
     * @param createdBy the actor (user or system) triggering instantiation
     * @return the newly created PENDING WorkItem with all template defaults applied
     */
    @Transactional
    public WorkItem instantiate(
            final WorkItemTemplate template,
            final String titleOverride,
            final String assigneeIdOverride,
            final String createdBy) {

        final WorkItemCreateRequest request = toCreateRequest(template, titleOverride, assigneeIdOverride, createdBy);
        WorkItem workItem = workItemService.create(request);

        // Apply template labels as MANUAL — the filter engine may add INFERRED on top
        final List<WorkItemLabel> labels = parseLabels(template);
        for (final WorkItemLabel label : labels) {
            workItem = workItemService.addLabel(workItem.id, label.path, label.appliedBy);
        }

        return workItem;
    }

    /**
     * Convert a template and optional overrides into a {@link WorkItemCreateRequest}.
     *
     * <p>
     * Static for unit testability — no CDI or JPA dependency.
     *
     * @param template the template providing defaults
     * @param titleOverride if non-null and non-blank, used as the title; otherwise template name
     * @param assigneeIdOverride if non-null, set as the direct assignee
     * @param createdBy the actor triggering the instantiation
     * @return the create request ready for {@link WorkItemService#create}
     */
    public static WorkItemCreateRequest toCreateRequest(
            final WorkItemTemplate template,
            final String titleOverride,
            final String assigneeIdOverride,
            final String createdBy) {

        final String title = (titleOverride != null && !titleOverride.isBlank())
                ? titleOverride
                : template.name;

        return new WorkItemCreateRequest(
                title,
                template.description,
                template.category,
                null, // formKey — not templated
                template.priority,
                assigneeIdOverride,
                template.candidateGroups,
                template.candidateUsers,
                template.requiredCapabilities,
                createdBy,
                template.defaultPayload,
                null, // claimDeadline — use config default unless template overrides
                null, // expiresAt — use config default unless template overrides
                null, // followUpDate
                null, // labels — applied separately so addLabel fires LABEL_ADDED events
                null, // confidenceScore — template-spawned items have no AI confidence
                null, // callerRef — not set for template-spawned items
                template.defaultClaimBusinessHours, // business hours claim deadline from template
                template.defaultExpiryBusinessHours); // business hours expiry from template
    }

    /**
     * Parse the template's {@link WorkItemTemplate#labelPaths} JSON array into
     * {@link WorkItemLabel} instances ready to be applied at instantiation.
     *
     * <p>
     * Returns an empty list if {@code labelPaths} is null, blank, or invalid JSON.
     * Labels are created with {@link LabelPersistence#MANUAL} and {@code appliedBy = "template"}.
     *
     * <p>
     * Static for unit testability — no CDI or JPA dependency.
     *
     * @param template the template whose labels are to be parsed
     * @return list of {@link WorkItemLabel} ready for application; may be empty
     */
    public static List<WorkItemLabel> parseLabels(final WorkItemTemplate template) {
        if (template.labelPaths == null || template.labelPaths.isBlank()) {
            return List.of();
        }
        try {
            final List<String> paths = MAPPER.readValue(template.labelPaths, new TypeReference<>() {
            });
            final List<WorkItemLabel> result = new ArrayList<>();
            for (final String path : paths) {
                if (path != null && !path.isBlank()) {
                    result.add(new WorkItemLabel(path, LabelPersistence.MANUAL, "template"));
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Find a template by ID.
     *
     * @param templateId the UUID
     * @return the template, or empty if not found
     */
    @Transactional
    public Optional<WorkItemTemplate> findById(final UUID templateId) {
        return Optional.ofNullable(WorkItemTemplate.findById(templateId));
    }
}
