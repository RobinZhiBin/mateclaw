package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TrustedWorkbenchContext;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.i18n.I18nService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * Verifies that {@link LimitExceededNode} surfaces fallback strings via
 * {@link I18nService} (RFC: prompt-cleanup E2) instead of literal Chinese
 * hardcodes. Two paths are covered:
 *
 * <ul>
 *   <li>Empty observation history — the inline {@code contextForLLM}
 *       defaults to {@code i18n.msg("agent.limit_exceeded.empty_context")}</li>
 *   <li>LLM returns empty text — the {@code finalAnswerDraft} fallback
 *       comes from {@code i18n.msg("agent.limit_exceeded.fallback")}</li>
 * </ul>
 */
class LimitExceededNodeFallbackTest {

    private ChatModel chatModel;
    private ObservationProcessor observationProcessor;
    private NodeStreamingChatHelper streamingHelper;
    private I18nService i18n;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        observationProcessor = mock(ObservationProcessor.class);
        when(observationProcessor.getMaxTotalObservationChars()).thenReturn(24000);
        when(observationProcessor.truncate(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

        streamingHelper = mock(NodeStreamingChatHelper.class);

        i18n = mock(I18nService.class);
        when(i18n.msg("agent.limit_exceeded.empty_context")).thenReturn("CANNED_EMPTY_CTX");
        when(i18n.msg("agent.limit_exceeded.fallback")).thenReturn("CANNED_FALLBACK");
    }

    private LimitExceededNode createNode() {
        return new LimitExceededNode(chatModel, observationProcessor, streamingHelper, i18n);
    }

    @Test
    @DisplayName("Empty LLM response → finalAnswerDraft uses i18n fallback (not Chinese literal)")
    void emptyLlmResponse_usesI18nFallback() throws Exception {
        // LLM returns null text → triggers the i18n fallback branch.
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                null, "", new AssistantMessage(""), List.of(), false, 0, 0);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStateWithObservations());

        assertEquals("CANNED_FALLBACK", output.get(FINAL_ANSWER_DRAFT),
                "finalAnswerDraft must come from i18n.msg(\"agent.limit_exceeded.fallback\") when the LLM returns nothing");
    }

    @Test
    @DisplayName("Non-empty LLM response → finalAnswerDraft uses LLM text (i18n untouched)")
    void nonEmptyLlmResponse_usesLlmText() throws Exception {
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "real answer", "", new AssistantMessage("real answer"), List.of(), false, 10, 5);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStateWithObservations());

        assertEquals("real answer", output.get(FINAL_ANSWER_DRAFT));
    }

    @Test
    void terminalAnswerKeepsTrustedProfileBeforeTheCurrentTask() throws Exception {
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "real answer", "", new AssistantMessage("real answer"), List.of(), false, 10, 5);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);
        Map<String, Object> values = new HashMap<>(buildStateValues());
        values.put(CHAT_ORIGIN, trustedOrigin());

        createNode().apply(new OverAllState(values));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(streamingHelper).streamCall(any(), prompt.capture(), anyString(), anyString());
        assertThat(prompt.getValue().getInstructions()).hasSize(3);
        assertThat(prompt.getValue().getInstructions().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getValue().getInstructions().get(1)).isInstanceOf(SystemMessage.class);
        assertThat(prompt.getValue().getInstructions().get(1).getText()).contains("PMO");
        assertThat(prompt.getValue().getInstructions().get(2)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getValue().getInstructions().get(2).getText()).contains("hello");
    }

    private OverAllState buildStateWithObservations() {
        return new OverAllState(buildStateValues());
    }

    private Map<String, Object> buildStateValues() {
        Map<String, Object> map = new HashMap<>();
        map.put(CONVERSATION_ID, "test-conv");
        map.put(USER_MESSAGE, "hello");
        map.put(MAX_ITERATIONS, 5);
        map.put(CURRENT_ITERATION, 5);
        // Non-empty observations so contextForLLM doesn't take the empty-context branch
        // (that branch is exercised separately by an integration test, hard to mock here
        // because OverAllState.value() may return immutable empty list defaults).
        map.put(OBSERVATION_HISTORY, List.of("obs1", "obs2"));
        return map;
    }

    private static ChatOrigin trustedOrigin() {
        TrustedWorkbenchContext context = new TrustedWorkbenchContext(
                3, new TrustedWorkbenchContext.Organization("PMO", "Owner", null),
                null, null, null, null);
        return ChatOrigin.web("test-conv", "visitor", 1L, null)
                .withTrustedWorkbenchContext(context, true, true);
    }
}
