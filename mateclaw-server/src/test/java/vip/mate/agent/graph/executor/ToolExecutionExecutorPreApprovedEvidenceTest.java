package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionExecutorPreApprovedEvidenceTest {

    @Test
    void replayCollectsWikiEvidenceBeforeInlineTruncation() {
        String raw = "{\"filler\":\"" + "x".repeat(9_000)
                + "\",\"chunks\":[{\"index\":1,\"chunkId\":\"secret-chunk\","
                + "\"rawTitle\":\"Operations Guide\",\"section\":\"Recovery\","
                + "\"pageNumber\":8,\"slug\":\"operations-guide\"}]}";
        ToolCallback callback = callback("wiki_search_pages", raw);
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(callback));
        ToolGuard allow = (name, arguments) -> ToolGuardResult.allow();
        ToolExecutionExecutor executor = new ToolExecutionExecutor(toolSet, allow, null, null);

        ToolExecutionExecutor.PreApprovedExecutionResult result =
                executor.executePreApprovedWithEvidence(
                        new AssistantMessage.ToolCall(
                                "call-1", "function", "wiki_search_pages", "{}"),
                        "{}", new ArrayList<>(), "conversation", null, new ArrayList<>());

        assertThat(result.response().responseData()).contains("TRUNCATED");
        assertThat(result.rawEvidenceLedger().hasWikiPageTitle("Operations Guide")).isTrue();
        assertThat(result.rawEvidenceLedger().hasWikiChunkId("secret-chunk")).isTrue();
    }

    private static ToolCallback callback(String name, String result) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String arguments) { return result; }
            @Override public String call(String arguments, ToolContext context) { return result; }
        };
    }
}
