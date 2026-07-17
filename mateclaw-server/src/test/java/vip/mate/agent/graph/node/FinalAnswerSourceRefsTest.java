package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.state.SourceEvidenceLedger;
import vip.mate.workbench.evidence.SafeSourceRef;
import vip.mate.workbench.evidence.SafeSourceRefProjector;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static vip.mate.agent.graph.state.MateClawStateKeys.FINAL_ANSWER;
import static vip.mate.agent.graph.state.MateClawStateKeys.PENDING_EVENTS;
import static vip.mate.agent.graph.state.MateClawStateKeys.SOURCE_EVIDENCE_LEDGER;

class FinalAnswerSourceRefsTest {

    @Test
    void emitsOneTypedSourceEventBeforeFinishReason() throws Exception {
        SourceEvidenceLedger ledger = wikiLedger("""
                {"pages":[
                  {"index":9,"slug":"operating-guide","title":" Operating\\u0000 Guide ","section":"Runbook","pageNumber":2},
                  {"index":20,"title":"Policy","section":"Controls","pageNumber":7,"chunkId":"secret-chunk-77"}
                ]}
                """);

        Map<String, Object> output = new FinalAnswerNode().apply(new OverAllState(Map.of(
                FINAL_ANSWER, "See [9] and [20].",
                SOURCE_EVIDENCE_LEDGER, ledger)));

        List<GraphEventPublisher.GraphEvent> events = events(output);
        assertThat(events).filteredOn(e -> "source_refs".equals(e.type())).hasSize(1);
        int sources = indexOf(events, "source_refs");
        int finish = indexOf(events, GraphEventPublisher.EVENT_FINISH_REASON);
        assertThat(sources).isGreaterThanOrEqualTo(0).isLessThan(finish);

        @SuppressWarnings("unchecked")
        List<SafeSourceRef> items = (List<SafeSourceRef>) events.get(sources).data().get("items");
        assertThat(items).extracting(SafeSourceRef::order).containsExactly(1, 2);
        assertThat(items).extracting(SafeSourceRef::sourceId)
                .containsExactly("operating-guide", "wiki-citation-2");
        assertThat(items).extracting(SafeSourceRef::locator)
                .containsExactly("Runbook · page 2", "Controls · page 7");
        assertThat(items.toString()).doesNotContain("secret-chunk-77", "/", "chunkId");
    }

    @Test
    void projectorRejectsPathShapedTitlesAndNeverScrapesMarkdown() throws Exception {
        SourceEvidenceLedger unsafe = wikiLedger("""
                {"pages":[{"index":4,"slug":"safe-slug","title":"/srv/private/page.md"}]}
                """);
        assertThat(SafeSourceRefProjector.from(unsafe)).isEmpty();

        Map<String, Object> output = new FinalAnswerNode().apply(new OverAllState(Map.of(
                FINAL_ANSWER, "Answer\n\n## Sources\n[1] invented-source")));
        GraphEventPublisher.GraphEvent event = events(output).stream()
                .filter(e -> "source_refs".equals(e.type()))
                .findFirst().orElseThrow();
        assertThat((List<?>) event.data().get("items")).isEmpty();
    }

    @Test
    void projectorUsesPageLocatorForRealWikiSearchShape() {
        SourceEvidenceLedger pageResult = wikiLedger("""
                {
                  "kbId":91477011,
                  "query":"operating guide",
                  "mode":"hybrid",
                  "matchCount":1,
                  "pages":[{
                    "slug":"operating-guide",
                    "title":"Operating Guide",
                    "snippet":"How to operate safely",
                    "matchedBy":"keyword",
                    "reason":"",
                    "score":"0.9000"
                  }]
                }
                """);

        assertThat(SafeSourceRefProjector.from(pageResult))
                .extracting(SafeSourceRef::sourceId, SafeSourceRef::locator)
                .containsExactly(tuple("operating-guide", "page"));
    }

    @Test
    void projectorUsesPageLocatorForRealWikiReadShape() {
        SourceEvidenceLedger pageResult = wikiLedger("wiki_read_page", """
                {
                  "title":"Operating Guide",
                  "slug":"operating-guide",
                  "version":3,
                  "lastUpdatedBy":"operator",
                  "content":"# Operating Guide",
                  "sourceFiles":[]
                }
                """);

        assertThat(SafeSourceRefProjector.from(pageResult))
                .extracting(SafeSourceRef::sourceId, SafeSourceRef::locator)
                .containsExactly(tuple("operating-guide", "page"));
    }

    @Test
    void projectorOmitsCitationWithoutSafeSlugOrLocator() {
        SourceEvidenceLedger noLocator = wikiLedger("""
                {"pages":[{"index":1,"title":"Guide"}]}
                """);

        assertThat(SafeSourceRefProjector.from(noLocator)).isEmpty();
    }

    @Test
    void projectorFallsBackForInternalIdentifierShapedSlugs() {
        SourceEvidenceLedger ledger = wikiLedger("""
                {"pages":[
                  {"index":1,"slug":"2077966908703842305","title":"One","section":"Intro"},
                  {"index":2,"slug":"123e4567-e89b-12d3-a456-426614174000","title":"Two","section":"Intro"}
                ]}
                """);

        assertThat(SafeSourceRefProjector.from(ledger)).extracting(SafeSourceRef::sourceId)
                .containsExactly("wiki-citation-1", "wiki-citation-2");
    }

    private static SourceEvidenceLedger wikiLedger(String json) {
        return wikiLedger("wiki_search_pages", json);
    }

    private static SourceEvidenceLedger wikiLedger(String toolName, String json) {
        return SourceEvidenceLedger.fromToolResponses(List.of(
                new ToolResponseMessage.ToolResponse("call-internal", toolName, json)));
    }

    @SuppressWarnings("unchecked")
    private static List<GraphEventPublisher.GraphEvent> events(Map<String, Object> output) {
        return (List<GraphEventPublisher.GraphEvent>) output.get(PENDING_EVENTS);
    }

    private static int indexOf(List<GraphEventPublisher.GraphEvent> events, String type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.equals(events.get(i).type())) return i;
        }
        return -1;
    }
}
