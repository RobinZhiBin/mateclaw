package vip.mate.workbench.evidence;

import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.SourceEvidenceLedger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FinalAnswerEvents {
    private FinalAnswerEvents() {}

    public static List<GraphEventPublisher.GraphEvent> terminal(
            SourceEvidenceLedger ledger, GraphEventPublisher.GraphEvent... terminalEvents) {
        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>(terminalEvents.length + 1);
        events.add(GraphEventPublisher.sourceRefs(SafeSourceRefProjector.from(ledger)));
        events.addAll(Arrays.asList(terminalEvents));
        return List.copyOf(events);
    }
}
