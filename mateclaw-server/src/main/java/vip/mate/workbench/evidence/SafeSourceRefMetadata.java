package vip.mate.workbench.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SafeSourceRefMetadata {
    public static final String KEY = "sourceRefs";

    private SafeSourceRefMetadata() {}

    public static String encode(ObjectMapper mapper, List<SafeSourceRef> items) {
        try {
            List<SafeSourceRef> safeItems = items == null ? List.of() : List.copyOf(items);
            return mapper.writeValueAsString(Map.of(KEY, safeItems));
        } catch (Exception exception) {
            throw new IllegalStateException("Source reference serialization failed", exception);
        }
    }

    public static List<SafeSourceRef> decode(ObjectMapper mapper, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return List.of();
        try {
            String json = unwrap(mapper, metadataJson.trim());
            JsonNode items = mapper.readTree(json).path(KEY);
            if (!items.isArray()) return List.of();
            List<SafeSourceRef> refs = new ArrayList<>();
            for (JsonNode item : items) refs.add(mapper.treeToValue(item, SafeSourceRef.class));
            for (int i = 0; i < refs.size(); i++) {
                if (refs.get(i).order() != i + 1) return List.of();
            }
            return List.copyOf(refs);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String unwrap(ObjectMapper mapper, String json) throws Exception {
        return json.startsWith("\"") && json.endsWith("\"")
                ? mapper.readValue(json, String.class) : json;
    }
}
