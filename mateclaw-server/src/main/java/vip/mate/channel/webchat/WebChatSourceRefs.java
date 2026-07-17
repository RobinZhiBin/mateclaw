package vip.mate.channel.webchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.workbench.evidence.SafeSourceRef;
import vip.mate.workbench.evidence.SafeSourceRefMetadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WebChatSourceRefs {
    private final ObjectMapper mapper;
    private boolean accepted;
    private List<SafeSourceRef> items = List.of();

    WebChatSourceRefs(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Optional<VisitorEvent> accept(String eventType, Map<String, Object> data) {
        if (!"source_refs".equals(eventType)) return Optional.empty();
        if (accepted || data == null) throw new InvalidSourceRefsException();
        try {
            JsonNode rawItems = mapper.valueToTree(data.get("items"));
            if (!rawItems.isArray()) throw new InvalidSourceRefsException();
            List<SafeSourceRef> parsed = new ArrayList<>();
            for (JsonNode rawItem : rawItems) parsed.add(mapper.treeToValue(rawItem, SafeSourceRef.class));
            for (int i = 0; i < parsed.size(); i++) {
                if (parsed.get(i).order() != i + 1) throw new InvalidSourceRefsException();
            }
            items = List.copyOf(parsed);
            accepted = true;
            return Optional.of(new VisitorEvent("source_refs", Map.of("items", items)));
        } catch (InvalidSourceRefsException exception) {
            throw exception;
        } catch (Exception ignored) {
            throw new InvalidSourceRefsException();
        }
    }

    List<SafeSourceRef> items() {
        return items;
    }

    String metadataJson() {
        if (!accepted) return "{}";
        try {
            return SafeSourceRefMetadata.encode(mapper, items);
        } catch (Exception ignored) {
            throw new InvalidSourceRefsException();
        }
    }

    void requireAccepted() {
        if (!accepted) throw new InvalidSourceRefsException();
    }

    void failIfPersistenceIsRequired() {
        if (accepted) throw new InvalidSourceRefsException();
    }

    record VisitorEvent(String name, Map<String, Object> payload) {}

    static final class InvalidSourceRefsException extends RuntimeException {}
}
