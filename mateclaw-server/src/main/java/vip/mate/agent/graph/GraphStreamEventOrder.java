package vip.mate.agent.graph;

import com.alibaba.cloud.ai.graph.NodeOutput;
import vip.mate.agent.AgentService;
import vip.mate.agent.GraphEventPublisher;

import java.util.ArrayList;
import java.util.List;

public final class GraphStreamEventOrder {

    private GraphStreamEventOrder() {}

    public static EventBatch partitionNewEvents(NodeOutput output, int alreadySent) {
        List<GraphEventPublisher.GraphEvent> allEvents = GraphEventPublisher.extractEvents(output);
        int start = Math.min(Math.max(alreadySent, 0), allEvents.size());
        List<AgentService.StreamDelta> beforeContent = new ArrayList<>();
        List<AgentService.StreamDelta> afterContent = new ArrayList<>();
        boolean terminalEvidenceStarted = false;

        for (int i = start; i < allEvents.size(); i++) {
            GraphEventPublisher.GraphEvent event = allEvents.get(i);
            if (GraphEventPublisher.EVENT_SOURCE_REFS.equals(event.type())) {
                terminalEvidenceStarted = true;
            }
            AgentService.StreamDelta delta =
                    AgentService.StreamDelta.event(event.type(), event.data());
            (terminalEvidenceStarted ? afterContent : beforeContent).add(delta);
        }

        return new EventBatch(allEvents.size(), List.copyOf(beforeContent), List.copyOf(afterContent));
    }

    public record EventBatch(
            int nextEventCount,
            List<AgentService.StreamDelta> beforeContent,
            List<AgentService.StreamDelta> afterContent) {}
}
