package io.quarkiverse.workitems.queues.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.MapContext;

import io.quarkiverse.workitems.runtime.model.WorkItem;

@ApplicationScoped
public class JexlConditionEvaluator implements FilterConditionEvaluator {

    private static final JexlEngine JEXL = new JexlBuilder()
            .strict(false).silent(true).create();

    @Override
    public String language() {
        return "jexl";
    }

    @Override
    public boolean evaluate(final WorkItem wi, final String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try {
            final var ctx = new MapContext();
            buildContext(wi).forEach(ctx::set);
            final Object result = JEXL.createExpression(expression).evaluate(ctx);
            return Boolean.TRUE.equals(result);
        } catch (JexlException e) {
            return false;
        }
    }

    private Map<String, Object> buildContext(final WorkItem wi) {
        // Use HashMap to support null values — MapContext.set() handles them via HashMap internally
        var map = new HashMap<String, Object>();
        map.put("status", wi.status != null ? wi.status.name() : "UNKNOWN");
        map.put("priority", wi.priority != null ? wi.priority.name() : "NORMAL");
        map.put("assigneeId", wi.assigneeId); // intentionally null-able for null-checks in expressions
        map.put("category", wi.category);
        map.put("title", wi.title != null ? wi.title : "");
        map.put("description", wi.description != null ? wi.description : "");
        map.put("candidateGroups", wi.candidateGroups != null ? wi.candidateGroups : "");
        var labelPaths = wi.labels != null
                ? wi.labels.stream().map(l -> l.path).toList()
                : List.of();
        map.put("labels", labelPaths);
        return map;
    }
}
