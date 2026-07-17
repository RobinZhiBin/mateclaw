package vip.mate.workbench;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.common.result.R;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.workbench.controller.WorkbenchCapabilityController;
import vip.mate.workbench.model.WorkbenchCapabilityManifest;
import vip.mate.workbench.service.WorkbenchCapabilityService;
import vip.mate.workbench.service.WorkbenchServiceKeyAuth;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class WorkbenchCapabilityControllerTest {

    @Mock private AgentMapper agentMapper;
    @Mock private AgentBindingService bindingService;
    @Mock private AvailableToolService availableToolService;
    private WorkbenchCapabilityController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WorkbenchCapabilityService service = new WorkbenchCapabilityService(
                agentMapper, bindingService, availableToolService);
        controller = new WorkbenchCapabilityController(
                new WorkbenchServiceKeyAuth("dedicated-secret"), service);
        mockMvc = standaloneSetup(controller).build();
    }

    @Test
    void distinguishesMissingAndInvalidDedicatedCredential() {
        assertThat(controller.capabilities(7L, null, "1").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.capabilities(7L, "wrong", "1").getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void blankServerConfigurationRemainsFailClosed() throws Exception {
        WorkbenchCapabilityService service = new WorkbenchCapabilityService(
                agentMapper, bindingService, availableToolService);
        MockMvc unavailable = standaloneSetup(new WorkbenchCapabilityController(
                new WorkbenchServiceKeyAuth("  "), service)).build();

        unavailable.perform(get("/api/v1/workbench/agents/7/capabilities")
                        .header("X-Workbench-Service-Key", "any-presented-key")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_SERVICE_FORBIDDEN"))
                .andExpect(jsonPath("$.data.retryable").value(false));
    }

    @Test
    void wrongWorkspaceAndMissingAgentHaveSameSafeResponse() {
        AgentEntity otherWorkspace = agent(7L, 2L);
        when(agentMapper.selectById(7L)).thenReturn(otherWorkspace);
        when(agentMapper.selectById(8L)).thenReturn(null);

        ResponseEntity<R<?>> wrong =
                controller.capabilities(7L, "dedicated-secret", "1");
        ResponseEntity<R<?>> missing =
                controller.capabilities(8L, "dedicated-secret", "1");

        assertThat(wrong.getStatusCode()).isEqualTo(missing.getStatusCode());
        assertThat(wrong.getBody().getCode()).isEqualTo(missing.getBody().getCode());
        assertThat(wrong.getBody().getMsg()).isEqualTo(missing.getBody().getMsg());
        assertThat(wrong.getBody().getData()).isEqualTo(missing.getBody().getData());
    }

    @Test
    void returnsOnlyFixedCapabilitiesAndFailsClosedWithoutPermission() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(Set.of(
                "extract_document_text", "execute_sql", "browser_use"));
        when(availableToolService.listHealthyRuntimeNames()).thenReturn(Set.of(
                "extract_document_text", "execute_sql", "browser_use"));

        ResponseEntity<R<?>> response =
                controller.capabilities(7L, "dedicated-secret", "1");
        WorkbenchCapabilityManifest manifest = manifest(response);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(manifest.fileAnalysis().available()).isTrue();
        assertThat(manifest.fileAnalysis().healthy()).isTrue();
        assertThat(manifest.structuredDataAnalysis().available()).isFalse();
        assertThat(manifest.structuredDataAnalysis().healthy()).isFalse();
        assertThat(manifest.structuredDataAnalysis().reasonCode()).isEqualTo("PERMISSION_UNVERIFIED");
        assertThat(manifest.authorizedFetch().available()).isFalse();
        assertThat(manifest.authorizedFetch().healthy()).isFalse();
        assertThat(manifest.authorizedFetch().reasonCode()).isEqualTo("PERMISSION_UNVERIFIED");
        assertThat(manifest.toString()).doesNotContain(
                "extract_document_text", "execute_sql", "browser_use", "workspaceId", "agentId");
    }

    @Test
    void requiresTheSameBoundToolToBeHealthy() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(Set.of("extract_document_text"));
        when(availableToolService.listHealthyRuntimeNames()).thenReturn(Set.of("read_file"));

        WorkbenchCapabilityManifest manifest = manifest(
                controller.capabilities(7L, "dedicated-secret", "1"));

        assertThat(manifest.fileAnalysis().available()).isFalse();
        assertThat(manifest.fileAnalysis().healthy()).isFalse();
        assertThat(manifest.fileAnalysis().reasonCode()).isEqualTo("UNHEALTHY");
    }

    @Test
    void delegationWithoutFileFunctionsRemainsNotConfigured() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(Set.of("delegateToAgent"));
        when(availableToolService.listHealthyRuntimeNames()).thenReturn(Set.of("read_file"));

        WorkbenchCapabilityManifest manifest = manifest(
                controller.capabilities(7L, "dedicated-secret", "1"));

        assertThat(manifest.fileAnalysis().available()).isFalse();
        assertThat(manifest.fileAnalysis().healthy()).isFalse();
        assertThat(manifest.fileAnalysis().reasonCode()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void nullAvailabilitySignalFailsClosed() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(Set.of("extract_document_text"));
        when(availableToolService.listHealthyRuntimeNames()).thenReturn(null);

        WorkbenchCapabilityManifest manifest = manifest(
                controller.capabilities(7L, "dedicated-secret", "1"));

        assertThat(manifest.fileAnalysis().reasonCode()).isEqualTo("PERMISSION_UNVERIFIED");
        assertThat(manifest.fileAnalysis().available()).isFalse();
    }

    @Test
    void availabilityFailureFreezesEveryCapability() {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(Set.of(
                "extract_document_text", "execute_sql", "browser_use"));
        when(availableToolService.listHealthyRuntimeNames())
                .thenThrow(new IllegalStateException("registry unavailable"));

        WorkbenchCapabilityManifest manifest = manifest(
                controller.capabilities(7L, "dedicated-secret", "1"));

        assertThat(manifest.fileAnalysis().reasonCode()).isEqualTo("PERMISSION_UNVERIFIED");
        assertThat(manifest.structuredDataAnalysis().reasonCode()).isEqualTo("PERMISSION_UNVERIFIED");
        assertThat(manifest.authorizedFetch().reasonCode()).isEqualTo("PERMISSION_UNVERIFIED");
    }

    @Test
    void httpRouteEnforcesHeadersAndReturnsOnlyThreeByThreeShape() throws Exception {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(bindingService.getEffectiveToolNames(7L)).thenReturn(Set.of("extract_document_text"));
        when(availableToolService.listHealthyRuntimeNames()).thenReturn(Set.of("extract_document_text"));

        String path = "/api/v1/workbench/agents/7/capabilities";
        mockMvc.perform(get(path).header("X-Workspace-Id", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_SERVICE_AUTH_REQUIRED"))
                .andExpect(jsonPath("$.data.retryable").value(false));
        mockMvc.perform(get(path)
                        .header("X-Workbench-Service-Key", "wrong")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_SERVICE_FORBIDDEN"))
                .andExpect(jsonPath("$.data.retryable").value(false));
        mockMvc.perform(get(path)
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "2"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_AGENT_NOT_FOUND"))
                .andExpect(jsonPath("$.data.retryable").value(false));
        String body = mockMvc.perform(get(path)
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("ok"))
                .andExpect(jsonPath("$.data.*", hasSize(3)))
                .andExpect(jsonPath("$.data.fileAnalysis.*", hasSize(3)))
                .andExpect(jsonPath("$.data.structuredDataAnalysis.*", hasSize(3)))
                .andExpect(jsonPath("$.data.authorizedFetch.*", hasSize(3)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("extract_document_text", "agentId", "workspaceId");
    }

    @Test
    void disabledAgentReturnsTheFrozenCapabilityState() {
        AgentEntity disabled = agent(7L, 1L);
        disabled.setEnabled(false);
        when(agentMapper.selectById(7L)).thenReturn(disabled);

        WorkbenchCapabilityManifest manifest = manifest(
                controller.capabilities(7L, "dedicated-secret", "1"));

        assertThat(manifest.fileAnalysis().reasonCode()).isEqualTo("AGENT_DISABLED");
        assertThat(manifest.structuredDataAnalysis().reasonCode()).isEqualTo("AGENT_DISABLED");
        assertThat(manifest.authorizedFetch().reasonCode()).isEqualTo("AGENT_DISABLED");
    }

    private static WorkbenchCapabilityManifest manifest(ResponseEntity<R<?>> response) {
        return (WorkbenchCapabilityManifest) response.getBody().getData();
    }

    private static AgentEntity agent(long id, long workspaceId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setWorkspaceId(workspaceId);
        agent.setEnabled(true);
        return agent;
    }

}
