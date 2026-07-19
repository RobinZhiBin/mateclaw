package vip.mate.workbench;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MockMvc;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.workbench.service.WorkbenchServiceKeyAuth;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class WorkbenchMemoryControllerTest {

    @Mock private AgentMapper agentMapper;
    @Mock private StructuredMemoryService structuredMemoryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        WorkbenchMemoryService service = new WorkbenchMemoryService(agentMapper, structuredMemoryService);
        mockMvc = standaloneSetup(new WorkbenchMemoryController(
                new WorkbenchServiceKeyAuth("dedicated-secret"), service)).build();
    }

    @Test
    void listRequiresDedicatedServiceCredential() throws Exception {
        mockMvc.perform(get(path()).header("X-Workspace-Id", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_SERVICE_AUTH_REQUIRED"));

        mockMvc.perform(get(path())
                        .header("X-Workbench-Service-Key", "wrong")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_SERVICE_FORBIDDEN"));

        verify(structuredMemoryService, never()).listWorkbenchEntries(eq(7L), anyString(), anyList());
    }

    @Test
    void listRejectsWrongWorkspaceAndMissingAgentWithoutLeakingWhichOne() throws Exception {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 2L));
        when(agentMapper.selectById(8L)).thenReturn(null);

        mockMvc.perform(get(path())
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_AGENT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/workbench/agents/8/visitors/visitor-a/memories")
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.errorCode").value("WORKBENCH_AGENT_NOT_FOUND"));
    }

    @Test
    void putDerivesOwnerKeyFromVisitorAndIgnoresRawOwnerKeyInBody() throws Exception {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));

        mockMvc.perform(put(path() + "/user/reply_style")
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1")
                        .contentType("application/json")
                        .content("{\"content\":\"Use concise Chinese.\",\"ownerKey\":\"api:attacker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("user"))
                .andExpect(jsonPath("$.data.key").value("reply_style"))
                .andExpect(jsonPath("$.data.content").value("Use concise Chinese."))
                .andExpect(jsonPath("$.data.source").value("workbench-user-confirmed"));

        ArgumentCaptor<String> owner = ArgumentCaptor.forClass(String.class);
        verify(structuredMemoryService).remember(eq(7L), eq("user"), eq("reply_style"),
                eq("Use concise Chinese."), eq("workbench-user-confirmed"), owner.capture());
        assertThat(owner.getValue()).isEqualTo("api:visitor-a");
    }

    @Test
    void listReturnsParsedMetadataWithoutRawOwnerKeyOrMarkdownMetadata() throws Exception {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));
        when(structuredMemoryService.listWorkbenchEntries(7L, "api:visitor-a", WorkbenchMemoryService.ALLOWED_TYPES))
                .thenReturn(List.of(new vip.mate.workbench.model.WorkbenchMemoryEntry(
                        "user", "reply_style", "Use concise Chinese.",
                        "workbench-user-confirmed", "2026-07-19")));

        String body = mockMvc.perform(get(path())
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].type").value("user"))
                .andExpect(jsonPath("$.data[0].key").value("reply_style"))
                .andExpect(jsonPath("$.data[0].source").value("workbench-user-confirmed"))
                .andExpect(jsonPath("$.data[0].updatedAt").value("2026-07-19"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("ownerKey", "api:visitor-a", "> Source:", "Updated:");
    }

    @Test
    void deleteEndpointsAreIdempotentAndClearOnlyAllowlistedTypes() throws Exception {
        when(agentMapper.selectById(7L)).thenReturn(agent(7L, 1L));

        mockMvc.perform(delete(path() + "/user/reply_style")
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(false));

        mockMvc.perform(delete(path())
                        .header("X-Workbench-Service-Key", "dedicated-secret")
                        .header("X-Workspace-Id", "1"))
                .andExpect(status().isOk());

        verify(structuredMemoryService).forget(7L, "user", "reply_style", "api:visitor-a");
        verify(structuredMemoryService).clearWorkbenchEntries(7L, "api:visitor-a", WorkbenchMemoryService.ALLOWED_TYPES);
    }

    private static String path() {
        return "/api/v1/workbench/agents/7/visitors/visitor-a/memories";
    }

    private static AgentEntity agent(long id, long workspaceId) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setWorkspaceId(workspaceId);
        agent.setEnabled(true);
        return agent;
    }
}
