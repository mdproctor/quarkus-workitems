package io.quarkiverse.workitems.dashboard;

import java.util.List;

public record WorkItemInfo(String id, String title, String priority, List<String> labelPaths) {

    /** Returns the review tier label (review/urgent, review/standard, review/routine), or null. */
    public String tier() {
        return labelPaths.stream()
                .filter(p -> p.equals("review/urgent") || p.equals("review/standard") || p.equals("review/routine"))
                .findFirst().orElse(null);
    }

    /** Returns the review state label (unassigned, claimed, active), or null. */
    public String state() {
        return labelPaths.stream()
                .filter(p -> p.endsWith("/unassigned") || p.endsWith("/claimed") || p.endsWith("/active"))
                .map(p -> {
                    if (p.endsWith("/unassigned")) return "unassigned";
                    if (p.endsWith("/claimed")) return "claimed";
                    return "active";
                })
                .findFirst().orElse(null);
    }
}
