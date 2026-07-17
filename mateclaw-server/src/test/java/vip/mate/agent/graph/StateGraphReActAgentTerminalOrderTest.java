package vip.mate.agent.graph;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.workspace.conversation.ConversationService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static vip.mate.agent.graph.state.MateClawStateKeys.CONTENT_STREAMED;
import static vip.mate.agent.graph.state.MateClawStateKeys.FINAL_ANSWER;
import static vip.mate.agent.graph.state.MateClawStateKeys.FINISH_REASON;
import static vip.mate.agent.graph.state.MateClawStateKeys.PENDING_EVENTS;

class StateGraphReActAgentTerminalOrderTest {

    @ParameterizedTest(name = "replay={0}, contentStreamed={1}")
    @CsvSource({
            "false, false",
            "false, true",
            "true, false",
            "true, true"
    })
    void finalContentAlwaysPrecedesSourceRefsAndTerminal(boolean replay, boolean contentStreamed) {
        CompiledGraph compiledGraph = mock(CompiledGraph.class);
        ConversationService conversations = mock(ConversationService.class);
        when(conversations.countMessages(any())).thenReturn(0L);
        when(conversations.listMessages(any())).thenReturn(List.of());

        Map<String, Object> state = new HashMap<>();
        state.put(FINAL_ANSWER, "final answer");
        state.put(CONTENT_STREAMED, contentStreamed);
        state.put(FINISH_REASON, "normal");
        state.put(PENDING_EVENTS, List.of(
                GraphEventPublisher.sourceRefs(List.of()),
                GraphEventPublisher.finishReason("normal")));
        NodeOutput output = NodeOutput.of(
                "final_answer", "agent", new OverAllState(state), null);
        when(compiledGraph.stream(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Flux.just(output));

        StateGraphReActAgent agent = new StateGraphReActAgent(
                mock(ChatClient.class), conversations, compiledGraph, null, null);

        List<AgentService.StreamDelta> deltas = (replay
                ? agent.chatWithReplayStream("question", "conversation", "{}", "member")
                : agent.chatStructuredStream("question", "conversation", "member"))
                .collectList()
                .block();

        int lastContent = lastIndexOfContent(deltas);
        int sourceRefs = indexOfEvent(deltas, GraphEventPublisher.EVENT_SOURCE_REFS);
        int terminal = indexOfEvent(deltas, GraphEventPublisher.EVENT_FINISH_REASON);

        assertTrue(lastContent >= 0, "the real agent stream must contain final content");
        assertTrue(lastContent < sourceRefs,
                "all content deltas must be emitted before source_refs");
        assertTrue(sourceRefs < terminal,
                "source_refs must be emitted before the terminal event");
        assertEquals(1, deltas.stream()
                .filter(delta -> GraphEventPublisher.EVENT_SOURCE_REFS.equals(delta.eventType()))
                .count(), "source_refs must be emitted exactly once");
        assertEquals(contentStreamed, deltas.get(lastContent).persistenceOnly());
    }

    private static int lastIndexOfContent(List<AgentService.StreamDelta> deltas) {
        int index = -1;
        for (int i = 0; i < deltas.size(); i++) {
            AgentService.StreamDelta delta = deltas.get(i);
            if (!delta.isEvent() && delta.content() != null && !delta.content().isEmpty()) {
                index = i;
            }
        }
        return index;
    }

    private static int indexOfEvent(List<AgentService.StreamDelta> deltas, String eventType) {
        for (int i = 0; i < deltas.size(); i++) {
            if (eventType.equals(deltas.get(i).eventType())) {
                return i;
            }
        }
        return -1;
    }
}
