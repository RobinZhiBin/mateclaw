package vip.mate.agent.context;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public record TrustedWorkbenchContext(
        Integer profileVersion,
        Organization organization,
        Responsibilities responsibilities,
        Expertise expertise,
        OutputPreferences outputPreferences,
        CurrentContext currentContext
) {
    private static final int MAX_JSON_CHARS = 12_000;
    private static final int MAX_TEXT_CHARS = 500;
    private static final int MAX_ARRAY_ITEMS = 32;

    private static final Set<String> ROOT = Set.of("profileVersion", "organization", "responsibilities",
            "expertise", "outputPreferences", "currentContext");
    private static final Set<String> ORG = Set.of("department", "position", "team");
    private static final Set<String> RESP = Set.of("duties", "decisionRights");
    private static final Set<String> EXP = Set.of("industries", "products", "technologies", "businessTags");
    private static final Set<String> OUT = Set.of("language", "detailLevel", "format", "requiresSourcesByDefault");
    private static final Set<String> CUR = Set.of("projects", "stageGoals", "concerns");

    public static TrustedWorkbenchContext parse(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (!node.isObject()) throw new IllegalArgumentException("trustedWorkbenchContext must be an object");
        if (node.toString().length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("trustedWorkbenchContext is too large");
        }
        requireFields(node, ROOT, "trustedWorkbenchContext");
        return new TrustedWorkbenchContext(
                intField(node, "profileVersion"),
                organization(node.get("organization")),
                responsibilities(node.get("responsibilities")),
                expertise(node.get("expertise")),
                outputPreferences(node.get("outputPreferences")),
                currentContext(node.get("currentContext")));
    }

    private static Organization organization(JsonNode node) {
        if (missing(node)) return null;
        requireObject(node, "organization");
        requireFields(node, ORG, "organization");
        return new Organization(text(node, "department"), text(node, "position"), text(node, "team"));
    }

    private static Responsibilities responsibilities(JsonNode node) {
        if (missing(node)) return null;
        requireObject(node, "responsibilities");
        requireFields(node, RESP, "responsibilities");
        return new Responsibilities(textList(node, "duties"), textList(node, "decisionRights"));
    }

    private static Expertise expertise(JsonNode node) {
        if (missing(node)) return null;
        requireObject(node, "expertise");
        requireFields(node, EXP, "expertise");
        return new Expertise(textList(node, "industries"), textList(node, "products"),
                textList(node, "technologies"), textList(node, "businessTags"));
    }

    private static OutputPreferences outputPreferences(JsonNode node) {
        if (missing(node)) return null;
        requireObject(node, "outputPreferences");
        requireFields(node, OUT, "outputPreferences");
        Boolean requiresSources = boolField(node, "requiresSourcesByDefault");
        return new OutputPreferences(text(node, "language"), text(node, "detailLevel"),
                text(node, "format"), requiresSources);
    }

    private static CurrentContext currentContext(JsonNode node) {
        if (missing(node)) return null;
        requireObject(node, "currentContext");
        requireFields(node, CUR, "currentContext");
        return new CurrentContext(textList(node, "projects"), textList(node, "stageGoals"),
                textList(node, "concerns"));
    }

    private static void requireFields(JsonNode node, Set<String> allowed, String path) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown trustedWorkbenchContext field: " + path + "." + field);
            }
        });
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
    }

    private static Integer intField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (missing(value)) return null;
        if (!value.isInt() || value.intValue() < 0 || value.intValue() > 1_000_000) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.intValue();
    }

    private static Boolean boolField(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (missing(value)) return null;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be a boolean");
        return value.booleanValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (missing(value)) return null;
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be a string");
        String rawText = value.asText();
        rejectControlCharacters(rawText, field);
        String text = rawText.trim();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException(field + " is too large");
        return text;
    }

    private static List<String> textList(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (missing(value)) return List.of();
        if (!value.isArray()) throw new IllegalArgumentException(field + " must be an array");
        if (value.size() > MAX_ARRAY_ITEMS) throw new IllegalArgumentException(field + " is too large");
        List<String> items = new ArrayList<>(value.size());
        for (JsonNode item : value) {
            if (!item.isTextual()) throw new IllegalArgumentException(field + " items must be strings");
            String rawText = item.asText();
            rejectControlCharacters(rawText, field);
            String text = rawText.trim();
            if (text.length() > MAX_TEXT_CHARS) throw new IllegalArgumentException(field + " item is too large");
            if (!text.isEmpty()) items.add(text);
        }
        return List.copyOf(items);
    }

    private static void rejectControlCharacters(String text, String field) {
        boolean hasControlCharacter = text.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT);
        if (hasControlCharacter) {
            throw new IllegalArgumentException(field + " contains control characters");
        }
    }

    private static boolean missing(JsonNode node) {
        return node == null || node.isMissingNode();
    }

    public record Organization(String department, String position, String team) {}
    public record Responsibilities(List<String> duties, List<String> decisionRights) {}
    public record Expertise(List<String> industries, List<String> products,
                            List<String> technologies, List<String> businessTags) {}
    public record OutputPreferences(String language, String detailLevel, String format,
                                    Boolean requiresSourcesByDefault) {}
    public record CurrentContext(List<String> projects, List<String> stageGoals, List<String> concerns) {}
}
