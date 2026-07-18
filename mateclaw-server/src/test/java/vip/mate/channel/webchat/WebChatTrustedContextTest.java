package vip.mate.channel.webchat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.service.ChannelService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.workbench.service.WorkbenchServiceKeyAuth;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WebChatTrustedContextTest {

    private static final String API_KEY = "mc_webchat_key";
    private static final String SERVICE_KEY = "workbench-secret";

    @Mock private ChannelService channelService;
    @Mock private AgentService agentService;
    @Mock private ConversationService conversationService;
    @Mock private vip.mate.channel.web.ChatStreamTracker streamTracker;
    @Mock private ConversationCompletionPublisher completionPublisher;
    @Mock private MemoryOwnerResolver memoryOwnerResolver;
    @Mock private WebChatFileService fileService;
    @Mock private WebChatTokenRevocationService tokenRevocationService;
    @Mock private vip.mate.audit.service.AuditEventService auditService;
    @Mock private vip.mate.llm.routing.AgentBindingResolver agentBindingResolver;
    @Mock private vip.mate.skill.repository.SkillMapper skillMapper;
    @Mock private vip.mate.wiki.repository.WikiPageMapper wikiPageMapper;
    @Mock private vip.mate.wiki.repository.WikiKnowledgeBaseMapper wikiKbMapper;
    @Mock private vip.mate.approval.ApprovalWorkflowService approvalService;

    private WebChatController controller;

    @BeforeEach
    void setUp() throws Exception {
        controller = new WebChatController(
                channelService,
                agentService,
                conversationService,
                streamTracker,
                new ObjectMapper(),
                completionPublisher,
                memoryOwnerResolver,
                fileService,
                tokenRevocationService,
                auditService,
                agentBindingResolver,
                skillMapper,
                wikiPageMapper,
                wikiKbMapper,
                new WorkbenchServiceKeyAuth(SERVICE_KEY),
                approvalService);
        Field secret = WebChatController.class.getDeclaredField("visitorTokenSecret");
        secret.setAccessible(true);
        secret.set(controller, "visitor-token-secret-1234567890");
    }

    @Test
    void rejectsTrustedContextWhenWorkbenchServiceKeyIsMissing() {
        WebChatController.WebChatRequest request = requestWithTrustedProfile();
        when(channelService.listChannelsByType("webchat")).thenReturn(List.of(channel()));

        SseEmitter emitter = controller.chatStream(API_KEY, null, request);

        assertThat(emitter).isNotNull();
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsTrustedContextWhenWorkbenchServiceKeyIsInvalid() {
        WebChatController.WebChatRequest request = requestWithTrustedProfile();
        when(channelService.listChannelsByType("webchat")).thenReturn(List.of(channel()));

        SseEmitter emitter = controller.chatStream(API_KEY, "wrong", request);

        assertThat(emitter).isNotNull();
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsTrustedContextWhenWebChatApiKeyIsInvalidEvenWithValidWorkbenchServiceKey() {
        WebChatController.WebChatRequest request = requestWithTrustedProfile();
        when(channelService.listChannelsByType("webchat")).thenReturn(List.of(channel()));

        SseEmitter emitter = controller.chatStream("wrong-webchat-key", SERVICE_KEY, request);

        assertThat(emitter).isNotNull();
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }

    @Test
    void acceptsLegacyWebChatRequestWithoutWorkbenchServiceKey() {
        WebChatController.WebChatRequest request = new WebChatController.WebChatRequest();
        request.setMessage("hello");
        request.setVisitorId("visitor-1");
        when(channelService.listChannelsByType("webchat")).thenReturn(List.of(channel()));
        when(agentService.getAgent(42L)).thenReturn(agent());
        when(conversationService.getOrCreateWebchatConversation(any(), eq(42L), any(), eq(1L), any()))
                .thenReturn(null);
        when(memoryOwnerResolver.resolve(any())).thenReturn("api:visitor-1");
        AtomicReference<ChatOrigin> captured = new AtomicReference<>();
        when(agentService.chatStructuredStream(eq(42L), eq("hello"), any(), eq("visitor-1"), eq(null), any()))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(5));
                    return Flux.empty();
                });

        controller.chatStream(API_KEY, null, request);

        verify(agentService, timeout(1000))
                .chatStructuredStream(eq(42L), eq("hello"), any(), eq("visitor-1"), eq(null), any());
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().trustedWorkbenchContext()).isNull();
        assertThat(captured.get().memoryRecallEnabled()).isTrue();
        assertThat(captured.get().memoryLearningEnabled()).isTrue();
    }

    @Test
    void validWorkbenchServiceKeyCarriesTrustedContextAndMemorySwitchesOnOrigin(CapturedOutput output)
            throws Exception {
        WebChatController.WebChatRequest request = requestWithTrustedProfile();
        request.setMemoryRecallEnabled(BooleanNode.FALSE);
        request.setMemoryLearningEnabled(BooleanNode.FALSE);
        when(channelService.listChannelsByType("webchat")).thenReturn(List.of(channel()));
        when(agentService.getAgent(42L)).thenReturn(agent());
        when(conversationService.getOrCreateWebchatConversation(any(), eq(42L), any(), eq(1L), any()))
                .thenReturn(null);
        when(memoryOwnerResolver.resolve(any())).thenReturn("api:visitor-1");
        AtomicReference<ChatOrigin> captured = new AtomicReference<>();
        CountDownLatch invoked = new CountDownLatch(1);
        when(agentService.chatStructuredStream(eq(42L), eq("hello"), any(), eq("visitor-1"), eq(null), any()))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(5));
                    invoked.countDown();
                    return Flux.empty();
                });

        controller.chatStream(API_KEY, SERVICE_KEY, request);

        assertThat(invoked.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().trustedWorkbenchContext()).isNotNull();
        assertThat(captured.get().trustedWorkbenchContext().profileVersion()).isEqualTo(11);
        assertThat(captured.get().memoryRecallEnabled()).isFalse();
        assertThat(captured.get().memoryLearningEnabled()).isFalse();
        assertThat(output.getOut() + output.getErr()).doesNotContain("PMO");
    }

    @Test
    void preservesPresenceAndRejectsCoercedOrNullServerOnlyFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        for (String json : List.of(
                "{\"message\":\"hello\",\"memoryRecallEnabled\":\"false\"}",
                "{\"message\":\"hello\",\"memoryRecallEnabled\":0}",
                "{\"message\":\"hello\",\"memoryLearningEnabled\":null}",
                "{\"message\":\"hello\",\"trustedWorkbenchContext\":null}")) {
            WebChatController.WebChatRequest request =
                    mapper.readValue(json, WebChatController.WebChatRequest.class);
            assertThat(request.hasServerOnlyWorkbenchFields()).as(json).isTrue();
            assertThatThrownBy(request::validateServerOnlyWorkbenchFields)
                    .as(json)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        WebChatController.WebChatRequest legacy =
                mapper.readValue("{\"message\":\"hello\"}", WebChatController.WebChatRequest.class);
        assertThat(legacy.hasServerOnlyWorkbenchFields()).isFalse();
        assertThatCode(legacy::validateServerOnlyWorkbenchFields).doesNotThrowAnyException();
        assertThat(legacy.memoryRecallEnabled()).isTrue();
        assertThat(legacy.memoryLearningEnabled()).isTrue();
    }

    @Test
    void rejectsInvalidServerOnlyFieldAtTheAuthenticatedControllerBoundary() throws Exception {
        WebChatController.WebChatRequest request = new ObjectMapper().readValue(
                "{\"message\":\"hello\",\"memoryRecallEnabled\":\"false\"}",
                WebChatController.WebChatRequest.class);
        when(channelService.listChannelsByType("webchat")).thenReturn(List.of(channel()));

        controller.chatStream(API_KEY, SERVICE_KEY, request);

        verify(agentService, never()).getAgent(any());
        verify(agentService, never()).chatStructuredStream(any(), any(), any(), any(), any(), any());
    }

    private static WebChatController.WebChatRequest requestWithTrustedProfile() {
        WebChatController.WebChatRequest request = new WebChatController.WebChatRequest();
        request.setMessage("hello");
        request.setVisitorId("visitor-1");
        request.setTrustedWorkbenchContext(new ObjectMapper().createObjectNode()
                .put("profileVersion", 11)
                .set("organization", new ObjectMapper().createObjectNode().put("department", "PMO")));
        return request;
    }

    private static ChannelEntity channel() {
        ChannelEntity channel = new ChannelEntity();
        channel.setId(1L);
        channel.setAgentId(42L);
        channel.setWorkspaceId(1L);
        channel.setConfigJson("{\"api_key\":\"" + API_KEY + "\"}");
        channel.setEnabled(true);
        return channel;
    }

    private static vip.mate.agent.model.AgentEntity agent() {
        vip.mate.agent.model.AgentEntity agent = new vip.mate.agent.model.AgentEntity();
        agent.setId(42L);
        agent.setWorkspaceId(1L);
        return agent;
    }
}
