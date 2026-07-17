package vip.mate.channel.webchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.workbench.evidence.SafeSourceRef;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebChatSourceRefsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void forwardsSourceRefsOnceAndPersistsTheExactSafeList() throws Exception {
        List<SafeSourceRef> expected = List.of(
                new SafeSourceRef("wiki", "guide", "Guide", "Intro · page 1", null, 1));
        WebChatSourceRefs sourceRefs = new WebChatSourceRefs(mapper);

        var first = sourceRefs.accept("source_refs", Map.of(
                "items", expected,
                "chunkId", "must-not-forward"));

        assertThat(first).isPresent();
        assertThat(first.orElseThrow().name()).isEqualTo("source_refs");
        assertThat(first.orElseThrow().payload()).containsOnlyKeys("items");
        assertThatThrownBy(() -> sourceRefs.accept("source_refs", Map.of("items", expected)))
                .isInstanceOf(WebChatSourceRefs.InvalidSourceRefsException.class);
        assertThat(sourceRefs.items()).isEqualTo(expected);
        assertThatThrownBy(sourceRefs::failIfPersistenceIsRequired)
                .isInstanceOf(WebChatSourceRefs.InvalidSourceRefsException.class);

        JsonNode metadata = mapper.readTree(sourceRefs.metadataJson());
        assertThat(metadata.fieldNames()).toIterable().containsExactly("sourceRefs");
        assertThat(metadata.path("sourceRefs").toString())
                .isEqualTo(mapper.valueToTree(expected).toString());
        assertThat(metadata.toString()).doesNotContain("chunkId", "must-not-forward");
    }

    @Test
    void filtersInternalEventsAndMalformedSourcePayloads() {
        WebChatSourceRefs sourceRefs = new WebChatSourceRefs(mapper);

        assertThat(sourceRefs.accept("_routing_decision", Map.of("prompt", "hidden"))).isEmpty();
        assertThat(sourceRefs.accept("phase", null)).isEmpty();
        assertThatThrownBy(() -> sourceRefs.accept("source_refs", Map.of("items", List.of(
                Map.of("kind", "wiki", "sourceId", "/private/id", "title", "Guide",
                        "locator", "Intro", "url", "", "order", 1)))))
                .isInstanceOf(WebChatSourceRefs.InvalidSourceRefsException.class);
        assertThat(sourceRefs.items()).isEmpty();
        assertThat(sourceRefs.metadataJson()).isEqualTo("{}");
    }

    @Test
    void distinguishesAcceptedEmptyListFromLegacyMissingEvent() throws Exception {
        WebChatSourceRefs legacy = new WebChatSourceRefs(mapper);
        assertThat(legacy.metadataJson()).isEqualTo("{}");

        WebChatSourceRefs acceptedEmpty = new WebChatSourceRefs(mapper);
        assertThat(acceptedEmpty.accept("source_refs", Map.of("items", List.of())))
                .get().extracting(WebChatSourceRefs.VisitorEvent::payload)
                .isEqualTo(Map.of("items", List.of()));
        assertThat(mapper.readTree(acceptedEmpty.metadataJson()))
                .isEqualTo(mapper.readTree("{\"sourceRefs\":[]}"));
        assertThatThrownBy(acceptedEmpty::failIfPersistenceIsRequired)
                .isInstanceOf(WebChatSourceRefs.InvalidSourceRefsException.class);
    }

    @Test
    void failsClosedWhenSafeIdCarriesPathShapedMemberText() {
        WebChatSourceRefs sourceRefs = new WebChatSourceRefs(mapper);
        Map<String, Object> unsafe = Map.of(
                "kind", "wiki",
                "sourceId", "safe-id",
                "title", "/srv/private/page.md",
                "locator", "Intro",
                "order", 1);

        assertThatThrownBy(() -> sourceRefs.accept("source_refs", Map.of("items", List.of(unsafe))))
                .isInstanceOf(WebChatSourceRefs.InvalidSourceRefsException.class);
        assertThat(sourceRefs.items()).isEmpty();
    }

    @Test
    void valueRejectsUnspacedPathAndCredentialBearingUrl() {
        assertThatThrownBy(() -> new SafeSourceRef(
                "wiki", "safe-id", "folder/private.md", "Intro", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SafeSourceRef(
                "wiki", "safe-id", "Guide", "Intro", "http://user@host.example/page", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
