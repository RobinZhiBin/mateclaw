package vip.mate.agent.graph.plan;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.SourceEvidenceLedger;
import vip.mate.planning.service.PlanningService;
import vip.mate.workbench.evidence.SafeSourceRef;
import vip.mate.workspace.conversation.ConversationService;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static vip.mate.agent.graph.plan.state.PlanStateKeys.FINAL_SUMMARY;
import static vip.mate.agent.graph.state.MateClawStateKeys.CONTENT_STREAMED;
import static vip.mate.agent.graph.state.MateClawStateKeys.SOURCE_EVIDENCE_LEDGER;

class StateGraphPlanExecuteAgentSourceRefsTest {

    @ParameterizedTest(name = "path={0}, streamed={1}, replay={2}")
    @CsvSource({
            "direct_answer, true, false",
            "plan_summary, true, false",
            "summary_fallback, false, false",
            "return_direct, false, true"
    })
    void everyTerminalPathEmitsOneEmptySourceRefsAfterContent(
            String path, boolean streamed, boolean replay) {
        List<AgentService.StreamDelta> deltas = runAgent(
                List.of(output(path, streamed, SourceEvidenceLedger.empty())), replay);

        int content = lastContent(deltas);
        int sourceRefs = eventIndex(deltas, GraphEventPublisher.EVENT_SOURCE_REFS);
        int finishReason = eventIndex(deltas, GraphEventPublisher.EVENT_FINISH_REASON);

        assertThat(content).isGreaterThanOrEqualTo(0);
        assertThat(sourceRefs).isGreaterThan(content);
        assertThat(finishReason).isGreaterThan(sourceRefs);
        assertThat(deltas.stream()
                .filter(delta -> GraphEventPublisher.EVENT_SOURCE_REFS.equals(delta.eventType())))
                .hasSize(1);
        assertThat(deltas.get(sourceRefs).eventData().get("items")).isEqualTo(List.of());
    }

    @Test
    void wikiEvidenceIsProjectedSafelyInStableOrderAtGraphCompletion() {
        SourceEvidenceLedger ledger = wikiLedger();

        List<AgentService.StreamDelta> deltas = runAgent(List.of(
                output("first pass", true, ledger),
                output("final pass", true, ledger)), false);

        List<AgentService.StreamDelta> sourceEvents = deltas.stream()
                .filter(delta -> GraphEventPublisher.EVENT_SOURCE_REFS.equals(delta.eventType()))
                .toList();
        assertThat(sourceEvents).hasSize(1);
        @SuppressWarnings("unchecked")
        List<SafeSourceRef> refs = (List<SafeSourceRef>) sourceEvents.get(0).eventData().get("items");
        assertThat(refs).extracting(SafeSourceRef::title)
                .containsExactly("First Page", "Second Page");
        assertThat(refs).extracting(SafeSourceRef::sourceId)
                .containsExactly("first-page", "second-page");
        assertThat(sourceEvents.get(0).eventData().toString())
                .doesNotContain("chunk-secret", "/srv/wiki", "workspaceId", "agentId");
    }

    private static List<AgentService.StreamDelta> runAgent(
            List<NodeOutput> outputs, boolean replay) {
        CompiledGraph graph = mock(CompiledGraph.class);
        ConversationService conversations = mock(ConversationService.class);
        PlanningService planning = mock(PlanningService.class);
        when(conversations.countMessages(any())).thenReturn(0L);
        when(conversations.listMessages(any())).thenReturn(List.of());
        when(graph.stream(anyMap(), any(RunnableConfig.class))).thenReturn(Flux.fromIterable(outputs));

        StateGraphPlanExecuteAgent agent = new StateGraphPlanExecuteAgent(
                mock(ChatClient.class), conversations, graph, planning, null, null);
        Flux<AgentService.StreamDelta> stream = replay
                ? agent.chatWithReplayStream("question", "conversation", "{}")
                : agent.chatStructuredStream("question", "conversation", "member");
        return stream.collectList().block();
    }

    private static NodeOutput output(
            String summary, boolean streamed, SourceEvidenceLedger ledger) {
        Map<String, Object> state = new HashMap<>();
        state.put(FINAL_SUMMARY, summary);
        state.put(CONTENT_STREAMED, streamed);
        state.put(SOURCE_EVIDENCE_LEDGER, ledger);
        return NodeOutput.of("terminal", "plan", new OverAllState(state), null);
    }

    private static SourceEvidenceLedger wikiLedger() {
        Set<SourceEvidenceLedger.WikiCitation> citations = new LinkedHashSet<>();
        citations.add(new SourceEvidenceLedger.WikiCitation(
                2, "chunk-secret-2", "Second Page", "Details", 4, "second-page"));
        citations.add(new SourceEvidenceLedger.WikiCitation(
                1, "chunk-secret-1", "First Page", "Overview", 2, "first-page"));
        return new SourceEvidenceLedger(Set.of(), Set.of(), Set.of(),
                Set.of("First Page", "Second Page"),
                Set.of("chunk-secret-1", "chunk-secret-2"), citations);
    }

    private static int lastContent(List<AgentService.StreamDelta> deltas) {
        int result = -1;
        for (int i = 0; i < deltas.size(); i++) {
            AgentService.StreamDelta delta = deltas.get(i);
            if (!delta.isEvent() && delta.content() != null && !delta.content().isEmpty()) {
                result = i;
            }
        }
        return result;
    }

    private static int eventIndex(List<AgentService.StreamDelta> deltas, String type) {
        for (int i = 0; i < deltas.size(); i++) {
            if (type.equals(deltas.get(i).eventType())) return i;
        }
        return -1;
    }
}
