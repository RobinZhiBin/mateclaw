package vip.mate.workbench;

import org.springframework.stereotype.Service;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.workbench.model.WorkbenchMemoryEntry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class WorkbenchMemoryService {

    public static final List<String> ALLOWED_TYPES = List.of("user", "feedback", "project", "reference");
    static final String CONFIRMED_SOURCE = "workbench-user-confirmed";

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
        String trimmed = requireContent(content);
        structuredMemoryService.remember(agentId, type, key, trimmed, CONFIRMED_SOURCE, ownerKey(visitorId));
        return Optional.of(new WorkbenchMemoryEntry(type, key, trimmed, CONFIRMED_SOURCE, null));
    }

    public Optional<Boolean> forget(Long agentId, Long workspaceId, String visitorId, String type, String key) {
        if (!authorized(agentId, workspaceId)) return Optional.empty();
        validateType(type);
        return Optional.of(structuredMemoryService.forget(agentId, type, key, ownerKey(visitorId)));
    }

    public Optional<Integer> clear(Long agentId, Long workspaceId, String visitorId) {
        if (!authorized(agentId, workspaceId)) return Optional.empty();
        return Optional.of(structuredMemoryService.clearWorkbenchEntries(
                agentId, ownerKey(visitorId), ALLOWED_TYPES));
    }

    static String ownerKey(String visitorId) {
        if (visitorId == null || visitorId.isBlank()) {
            throw new IllegalArgumentException("visitorId is required");
        }
        return "api:" + visitorId.trim();
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

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
        return content.trim();
    }
}
