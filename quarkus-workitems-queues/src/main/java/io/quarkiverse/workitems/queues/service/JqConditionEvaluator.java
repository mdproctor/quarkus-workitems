package io.quarkiverse.workitems.queues.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;

import io.quarkiverse.workitems.runtime.model.WorkItem;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;

@ApplicationScoped
public class JqConditionEvaluator implements FilterConditionEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Scope ROOT_SCOPE;

    static {
        ROOT_SCOPE = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, ROOT_SCOPE);
    }

    @Override
    public String language() {
        return "jq";
    }

    @Override
    public boolean evaluate(final WorkItem wi, final String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        try {
            final JsonNode input = MAPPER.valueToTree(toMap(wi));
            final JsonQuery q = JsonQuery.compile(expression, Versions.JQ_1_6);
            final Scope scope = Scope.newChildScope(ROOT_SCOPE);
            final var results = new ArrayList<JsonNode>();
            q.apply(scope, input, results::add);
            if (results.isEmpty()) {
                return false;
            }
            final JsonNode first = results.get(0);
            return first instanceof BooleanNode && first.asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, Object> toMap(final WorkItem wi) {
        var map = new HashMap<String, Object>();
        map.put("status", wi.status != null ? wi.status.name() : null);
        map.put("priority", wi.priority != null ? wi.priority.name() : null);
        map.put("assigneeId", wi.assigneeId);
        map.put("category", wi.category);
        map.put("title", wi.title);
        map.put("description", wi.description);
        map.put("labels", wi.labels != null
                ? wi.labels.stream().map(l -> l.path).toList()
                : List.of());
        return map;
    }
}
