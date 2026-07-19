package vip.mate.workbench;

import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.workbench.model.WorkbenchMemoryEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class WorkbenchMemoryService {

    public static final List<String> ALLOWED_TYPES = List.of("user", "feedback", "project", "reference");
    static final String CONFIRMED_SOURCE = "workbench-user-confirmed";
    private static final Pattern VISITOR_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,124}");
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Pattern MARKDOWN_CONTROL_LINE = Pattern.compile(
            "(?m)^\\s*(?:##\\s+|>\\s*Source\\s*:)", Pattern.CASE_INSENSITIVE);

    private final AgentMapper agentMapper;
    private final StructuredMemoryService structuredMemoryService;

    public WorkbenchMemoryService(AgentMapper agentMapper, StructuredMemoryService structuredMemoryService) {
        this.agentMapper = agentMapper;
        this.structuredMemoryService = structuredMemoryService;
    }

    public Optional<List<WorkbenchMemoryEntry>> list(Long agentId, Long workspaceId, String visitorId) {
        if (!authorized(agentId, workspaceId)) return Optional.empty();
        return Optional.of(structuredMemoryService.listWorkbenchEntries(
                agentId, ownerKey(visitorId), ALLOWED_TYPES));
    }

    public Optional<WorkbenchMemoryEntry> remember(
            Long agentId, Long workspaceId, String visitorId, String type, String key, String content) {
        if (!authorized(agentId, workspaceId)) return Optional.empty();
        validateType(type);
        validateKey(key);
        String trimmed = requireContent(content);
        structuredMemoryService.remember(agentId, type, key, trimmed, CONFIRMED_SOURCE, ownerKey(visitorId));
        return Optional.of(new WorkbenchMemoryEntry(type, key, trimmed, CONFIRMED_SOURCE, null));
    }

    public Optional<Boolean> forget(Long agentId, Long workspaceId, String visitorId, String type, String key) {
        if (!authorized(agentId, workspaceId)) return Optional.empty();
        validateType(type);
        validateKey(key);
        return Optional.of(structuredMemoryService.forget(agentId, type, key, ownerKey(visitorId)));
    }

    public Optional<Integer> clear(Long agentId, Long workspaceId, String visitorId) {
        if (!authorized(agentId, workspaceId)) return Optional.empty();
        return Optional.of(structuredMemoryService.clearWorkbenchEntries(
                agentId, ownerKey(visitorId), ALLOWED_TYPES));
    }

    static String ownerKey(String visitorId) {
        String normalized = visitorId == null ? "" : visitorId.trim();
        if (!VISITOR_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("visitorId must be 1-124 safe identifier characters");
        }
        return "api:" + normalized;
    }

    private boolean authorized(Long agentId, Long workspaceId) {
        if (agentId == null || workspaceId == null) return false;
        AgentEntity agent = agentMapper.selectById(agentId);
        return agent != null
                && Boolean.TRUE.equals(agent.getEnabled())
                && Objects.equals(agent.getWorkspaceId(), workspaceId);
    }

    private static void validateType(String type) {
        if (!ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("Invalid memory type: " + type);
        }
    }

    private static void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid memory key");
        }
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        String trimmed = content.trim();
        if (MARKDOWN_CONTROL_LINE.matcher(trimmed).find()) {
            throw new IllegalArgumentException("content contains reserved Markdown control lines");
        }
        return trimmed;
    }
}
