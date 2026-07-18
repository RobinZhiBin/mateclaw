package vip.mate.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.AgentService;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TrustedWorkbenchContext;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.memory.lifecycle.MemoryLifecycleMediator;
import vip.mate.memory.listener.PostConversationMemoryListener;
import vip.mate.memory.lifecycle.MemoryLifecycleEventListener;
import vip.mate.memory.nudge.MemoryNudgeService;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.memory.service.MemorySummarizationService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemorySwitchIsolationTest {

    @Mock private MemorySummarizationService summarizationService;
    @Mock private MemoryNudgeService nudgeService;
    @Mock private MemoryLifecycleMediator lifecycleMediator;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ConversationService conversationService;
    @Mock private vip.mate.memory.identity.MemoryOwnerResolver memoryOwnerResolver;
    @Mock private AgentMapper agentMapper;
    @Mock private AgentGraphBuilder agentGraphBuilder;
    @Mock private MemoryRecallTracker memoryRecallTracker;
    @Mock private ConversationMapper conversationMapper;
    @Mock private BaseAgent baseAgent;

    private MemoryProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setAutoSummarizeEnabled(true);
        properties.setLifecycleMediatorEnabled(true);
        properties.setMinMessagesForSummarize(2);
        properties.setMinUserMessageLength(1);

        AgentEntity entity = new AgentEntity();
        entity.setId(9L);
        entity.setEnabled(true);
        lenient().when(agentMapper.selectById(9L)).thenReturn(entity);
        lenient().when(agentGraphBuilder.build(any(AgentEntity.class), any(), any())).thenReturn(baseAgent);
        lenient().when(baseAgent.chat(anyString(), anyString())).thenReturn("answer");
    }

    @Test
    void learningDisabledEventSkipsPostConversationExtractionAndNudge() {
        PostConversationMemoryListener listener =
                new PostConversationMemoryListener(properties, summarizationService, nudgeService);

        listener.onConversationCompleted(disabledLearningEvent());

        verify(summarizationService, never()).analyzeAndUpdateMemory(anyLong(), anyString(), any());
        verify(nudgeService, never()).maybeNudge(anyLong(), anyString(), anyInt(), any(), eq(false));
    }

    @Test
    void learningDisabledEventSkipsLifecycleSessionEnd() {
        MemoryLifecycleEventListener listener =
                new MemoryLifecycleEventListener(lifecycleMediator, properties);

        listener.onConversationCompleted(disabledLearningEvent());

        verify(lifecycleMediator, never()).onSessionEnd(anyLong(), anyString());
    }

    @Test
    void publisherStillEmitsCompletionEventForNonMemoryListenersWhenLearningIsDisabled() {
        when(conversationService.getMessageCount("conv-1")).thenReturn(4);
        ConversationCompletionPublisher publisher =
                new ConversationCompletionPublisher(eventPublisher, conversationService, memoryOwnerResolver);

        publisher.publish(9L, "conv-1", "question", "answer", "webchat", "api:visitor", false);

        verify(eventPublisher).publishEvent(new ConversationCompletedEvent(
                9L, "conv-1", "question", "answer", 4, "webchat", "api:visitor", false));
    }

    @Test
    void recallDisabledSkipsAgentServicePrefetchAndRecallTracking() {
        MemoryLifecycleMediator mediator = org.mockito.Mockito.mock(MemoryLifecycleMediator.class);
        AgentService service = agentService(mediator);
        ChatOrigin origin = trustedOrigin(false, true);

        service.chat(9L, "question", "conv-1", origin);

        verify(memoryRecallTracker, never()).trackRecalls(anyLong(), anyString());
        verify(mediator, never()).beforeLlmCall(any());
        verify(mediator).afterLlmCall(any(), eq("answer"));
    }

    @Test
    void learningDisabledSkipsAgentServiceAfterTurnSyncAndKeepsProfileOutOfUserQuery() {
        MemoryLifecycleMediator mediator = org.mockito.Mockito.mock(MemoryLifecycleMediator.class);
        when(mediator.beforeLlmCall(any())).thenReturn("<memory-context>prior fact</memory-context>");
        AgentService service = agentService(mediator);
        ChatOrigin origin = trustedOrigin(true, false);

        service.chat(9L, "question", "conv-1", origin);

        verify(memoryRecallTracker).trackRecalls(9L, "question");
        verify(mediator).beforeLlmCall(any());
        verify(mediator, never()).afterLlmCall(any(), anyString());
        org.mockito.ArgumentCaptor<String> userQuery =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(baseAgent).chat(userQuery.capture(), eq("conv-1"));
        assertThat(userQuery.getValue())
                .contains("prior fact")
                .contains("question")
                .doesNotContain("PMO")
                .doesNotContain("trusted workbench profile");
    }

    private static ConversationCompletedEvent disabledLearningEvent() {
        return new ConversationCompletedEvent(
                9L, "conv-1", "question", "answer", 4, "webchat", "api:visitor", false);
    }

    private AgentService agentService(MemoryLifecycleMediator mediator) {
        return new AgentService(agentMapper, agentGraphBuilder, memoryRecallTracker, mediator,
                properties, memoryOwnerResolver, conversationMapper);
    }

    private static ChatOrigin trustedOrigin(boolean recall, boolean learning) {
        TrustedWorkbenchContext context = new TrustedWorkbenchContext(
                3,
                new TrustedWorkbenchContext.Organization("PMO", "Owner", null),
                null, null, null, null);
        return ChatOrigin.web("conv-1", "visitor-1", 1L, null)
                .withTrustedWorkbenchContext(context, recall, learning);
    }
}
