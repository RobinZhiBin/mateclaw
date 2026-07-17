package vip.mate.workbench.service;

import org.springframework.stereotype.Service;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.workbench.model.WorkbenchCapabilityManifest;
import vip.mate.workbench.model.WorkbenchCapabilityManifest.CapabilityState;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkbenchCapabilityService {
    private static final Set<String> FILE_TOOLS = Set.of(
            "extract_document_text", "extract_pdf_text", "extract_word_text", "read_file");
    private static final Set<String> STRUCTURED_DATA_TOOLS = Set.of(
            "execute_sql", "query_datasource");
    private static final Set<String> FETCH_TOOLS = Set.of("web_search", "browser_use");

    private final AgentMapper agentMapper;
    private final AgentBindingService bindingService;
    private final AvailableToolService availableToolService;

    public WorkbenchCapabilityService(
            AgentMapper agentMapper,
            AgentBindingService bindingService,
            AvailableToolService availableToolService) {
        this.agentMapper = agentMapper;
        this.bindingService = bindingService;
        this.availableToolService = availableToolService;
    }

    public Optional<WorkbenchCapabilityManifest> resolve(Long agentId, Long workspaceId) {
        AgentEntity agent = agentId == null ? null : agentMapper.selectById(agentId);
        if (agent == null || workspaceId == null || !workspaceId.equals(agent.getWorkspaceId())) {
            return Optional.empty();
        }
        if (!Boolean.TRUE.equals(agent.getEnabled())) return Optional.of(agentDisabledManifest());

        Set<String> effective;
        List<AvailableToolDTO> available;
        try {
            effective = bindingService.getEffectiveToolNames(agentId);
            available = availableToolService.listAvailable();
        } catch (Exception ignored) {
            return Optional.of(permissionUnverifiedManifest());
        }
        if (effective == null) return Optional.of(notConfiguredManifest());
        if (available == null) return Optional.of(permissionUnverifiedManifest());

        Set<String> healthy = available.stream()
                .filter(tool -> tool != null && tool.isAvailable() && !tool.isStale())
                .map(AvailableToolDTO::getName)
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        CapabilityState file = fileState(effective, healthy);
        CapabilityState structured = authorizationState(effective, healthy, STRUCTURED_DATA_TOOLS);
        CapabilityState fetch = authorizationState(effective, healthy, FETCH_TOOLS);
        return Optional.of(new WorkbenchCapabilityManifest(file, structured, fetch));
    }

    private static CapabilityState fileState(Set<String> effective, Set<String> healthy) {
        if (!containsAny(effective, FILE_TOOLS)) return state(false, false, "NOT_CONFIGURED");
        if (!hasHealthyBinding(effective, healthy, FILE_TOOLS)) {
            return state(false, false, "UNHEALTHY");
        }
        return state(true, true, "AVAILABLE");
    }

    private static CapabilityState authorizationState(
            Set<String> effective, Set<String> healthy, Set<String> toolNames) {
        if (!containsAny(effective, toolNames)) return state(false, false, "NOT_CONFIGURED");
        if (!hasHealthyBinding(effective, healthy, toolNames)) {
            return state(false, false, "UNHEALTHY");
        }
        return state(false, false, "PERMISSION_UNVERIFIED");
    }

    private static boolean containsAny(Set<String> actual, Set<String> candidates) {
        return candidates.stream().anyMatch(actual::contains);
    }

    private static boolean hasHealthyBinding(
            Set<String> effective, Set<String> healthy, Set<String> candidates) {
        return candidates.stream().anyMatch(name -> effective.contains(name) && healthy.contains(name));
    }

    private static WorkbenchCapabilityManifest notConfiguredManifest() {
        CapabilityState state = state(false, false, "NOT_CONFIGURED");
        return new WorkbenchCapabilityManifest(state, state, state);
    }

    private static WorkbenchCapabilityManifest permissionUnverifiedManifest() {
        CapabilityState state = state(false, false, "PERMISSION_UNVERIFIED");
        return new WorkbenchCapabilityManifest(state, state, state);
    }

    private static WorkbenchCapabilityManifest agentDisabledManifest() {
        CapabilityState state = state(false, false, "AGENT_DISABLED");
        return new WorkbenchCapabilityManifest(state, state, state);
    }

    private static CapabilityState state(boolean available, boolean healthy, String reason) {
        return new CapabilityState(available, healthy, reason);
    }
}
