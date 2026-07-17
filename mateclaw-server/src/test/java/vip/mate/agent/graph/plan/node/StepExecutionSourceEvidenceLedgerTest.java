package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.SourceEvidenceLedger;
import vip.mate.planning.service.PlanningService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static vip.mate.agent.graph.state.MateClawStateKeys.AGENT_ID;
import static vip.mate.agent.graph.state.MateClawStateKeys.CONVERSATION_ID;
import static vip.mate.agent.graph.state.MateClawStateKeys.PRE_APPROVED_TOOL_CALL;
import static vip.mate.agent.graph.state.MateClawStateKeys.SOURCE_EVIDENCE_LEDGER;
import static vip.mate.agent.graph.state.MateClawStateKeys.SYSTEM_PROMPT;
import static vip.mate.agent.graph.state.MateClawStateKeys.WORKSPACE_BASE_PATH;

class StepExecutionSourceEvidenceLedgerTest {

    private final ToolExecutionExecutor executor = mock(ToolExecutionExecutor.class);
    private final PlanningService planning = mock(PlanningService.class);
    private final NodeStreamingChatHelper streaming = mock(NodeStreamingChatHelper.class);
    private final AssistantMessage.ToolCall wikiCall = new AssistantMessage.ToolCall(
            "call-1", "function", "wiki_search_pages", "{\"query\":\"policy\"}");
    private SourceEvidenceLedger wikiLedger;

    @BeforeEach
    void setUp() {
        wikiLedger = SourceEvidenceLedger.fromToolResponses(List.of(wikiResponse()));
        when(streaming.streamCall(any(), any(), anyString(), anyString()))
                .thenReturn(toolCallResult(), finalResult());
    }

    @Test
    void normalToolExecutionMergesRawLedgerIntoExistingState() throws Exception {
        when(executor.execute(anyList(), anyString(), anyString(), any(Boolean.class),
                anyString(), anyString(), any()))
                .thenReturn(new ToolExecutionExecutor.ToolExecutionResult(
                        List.of(wikiResponse()), List.of(), false, null, null,
                        List.of(), wikiLedger));

        Map<String, Object> output = node().apply(state(false));

        SourceEvidenceLedger merged = (SourceEvidenceLedger) output.get(SOURCE_EVIDENCE_LEDGER);
        assertThat(merged).isNotNull();
        assertThat(merged.hasPath("Existing.java")).isTrue();
        assertThat(merged.hasWikiPageTitle("Policy Guide")).isTrue();
    }

    @Test
    void preApprovedReplayMergesRawBeforeSpillLedger() throws Exception {
        when(executor.executePreApprovedWithEvidence(
                any(), anyString(), anyList(), anyString(), anyString(), anyList()))
                .thenReturn(new ToolExecutionExecutor.PreApprovedExecutionResult(
                        wikiResponse(), wikiLedger));

        Map<String, Object> output = node().apply(state(true));

        SourceEvidenceLedger merged = (SourceEvidenceLedger) output.get(SOURCE_EVIDENCE_LEDGER);
        assertThat(merged).isNotNull();
        assertThat(merged.hasPath("Existing.java")).isTrue();
        assertThat(merged.hasWikiPageTitle("Policy Guide")).isTrue();
    }

    private StepExecutionNode node() {
        return new StepExecutionNode(null,
                AgentToolSet.fromCallbacks(List.of(), List.of()),
                executor, planning, null, null, streaming, null);
    }

    private OverAllState state(boolean replay) {
        Map<String, Object> values = new HashMap<>();
        values.put(PlanStateKeys.PLAN_ID, 7L);
        values.put(PlanStateKeys.PLAN_STEPS, List.of("Search the wiki"));
        values.put(PlanStateKeys.CURRENT_STEP_INDEX, 0);
        values.put(PlanStateKeys.WORKING_CONTEXT, "");
        values.put(CONVERSATION_ID, "conversation");
        values.put(AGENT_ID, "11");
        values.put(WORKSPACE_BASE_PATH, "/workspace");
        values.put(SYSTEM_PROMPT, "system");
        values.put(SOURCE_EVIDENCE_LEDGER,
                SourceEvidenceLedger.empty().withSourcePath("Existing.java"));
        if (replay) {
            values.put(PRE_APPROVED_TOOL_CALL,
                    "{\"name\":\"wiki_search_pages\",\"arguments\":\"{\\\"query\\\":\\\"policy\\\"}\"}");
        }
        return new OverAllState(values);
    }

    private NodeStreamingChatHelper.StreamResult toolCallResult() {
        AssistantMessage assistant = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(wikiCall))
                .build();
        return new NodeStreamingChatHelper.StreamResult(
                "", "", assistant, List.of(wikiCall), true, 1, 1);
    }

    private NodeStreamingChatHelper.StreamResult finalResult() {
        return new NodeStreamingChatHelper.StreamResult(
                "step complete", "", new AssistantMessage("step complete"),
                List.of(), false, 1, 1);
    }

    private ToolResponseMessage.ToolResponse wikiResponse() {
        return new ToolResponseMessage.ToolResponse(
                "call-1", "wiki_search_pages",
                "{\"chunks\":[{\"index\":1,\"chunkId\":\"chunk-secret\","
                        + "\"rawTitle\":\"Policy Guide\",\"section\":\"Controls\","
                        + "\"pageNumber\":3,\"slug\":\"policy-guide\"}]}");
    }
}
