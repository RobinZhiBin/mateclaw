package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.planning.service.PlanningService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static vip.mate.agent.graph.state.MateClawStateKeys.CONTENT_STREAMED;
import static vip.mate.agent.graph.state.MateClawStateKeys.CONVERSATION_ID;
import static vip.mate.agent.graph.state.MateClawStateKeys.THINKING_STREAMED;

class PlanSummaryNodeFallbackTest {

    @Test
    void localFallbackIsBroadcastableEvenWhenPreviousStepWasStreamed() throws Exception {
        PlanningService planning = mock(PlanningService.class);
        NodeStreamingChatHelper streaming = mock(NodeStreamingChatHelper.class);
        when(streaming.streamCall(any(), any(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("provider unavailable"));
        Map<String, Object> values = new HashMap<>();
        values.put(PlanStateKeys.PLAN_ID, 7L);
        values.put(PlanStateKeys.GOAL, "Summarize findings");
        values.put(PlanStateKeys.COMPLETED_RESULTS, List.of("step result"));
        values.put(CONVERSATION_ID, "conversation");
        values.put(CONTENT_STREAMED, true);
        values.put(THINKING_STREAMED, true);

        Map<String, Object> output = new PlanSummaryNode(null, planning, streaming)
                .apply(new OverAllState(values));

        assertThat(output.get(PlanStateKeys.FINAL_SUMMARY).toString())
                .contains("Summarize findings", "step result");
        assertThat(output.get(CONTENT_STREAMED)).isEqualTo(false);
        assertThat(output.get(THINKING_STREAMED)).isEqualTo(false);
    }
}
