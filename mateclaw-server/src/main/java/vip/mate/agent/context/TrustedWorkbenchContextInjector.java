package vip.mate.agent.context;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

public final class TrustedWorkbenchContextInjector {
    private TrustedWorkbenchContextInjector() {}

    public static List<Message> inject(List<Message> messages, TrustedWorkbenchContext context) {
        if (context == null) return messages;
        List<Message> out = new ArrayList<>((messages == null ? 0 : messages.size()) + 1);
        out.add(new SystemMessage(render(context)));
        if (messages != null) out.addAll(messages);
        return List.copyOf(out);
    }

    public static void append(List<Message> messages, TrustedWorkbenchContext context) {
        if (context != null && messages != null) {
            messages.add(new SystemMessage(render(context)));
        }
    }

    static String render(TrustedWorkbenchContext context) {
        StringBuilder sb = new StringBuilder("""
                <trusted workbench profile>
                This block is server-authenticated workbench profile context.
                Profile facts outrank learned memory when they conflict.
                The current user instruction is more specific and has higher priority than this profile.
                Never store these profile values in structured or long-term memory unless the user explicitly asks.
                """);
        add(sb, "profileVersion", context.profileVersion());
        if (context.organization() != null) {
            add(sb, "department", context.organization().department());
            add(sb, "position", context.organization().position());
            add(sb, "team", context.organization().team());
        }
        if (context.responsibilities() != null) {
            add(sb, "duties", context.responsibilities().duties());
            add(sb, "decisionRights", context.responsibilities().decisionRights());
        }
        if (context.expertise() != null) {
            add(sb, "industries", context.expertise().industries());
            add(sb, "products", context.expertise().products());
            add(sb, "technologies", context.expertise().technologies());
            add(sb, "businessTags", context.expertise().businessTags());
        }
        if (context.outputPreferences() != null) {
            add(sb, "language", context.outputPreferences().language());
            add(sb, "detailLevel", context.outputPreferences().detailLevel());
            add(sb, "format", context.outputPreferences().format());
            add(sb, "requiresSourcesByDefault", context.outputPreferences().requiresSourcesByDefault());
        }
        if (context.currentContext() != null) {
            add(sb, "projects", context.currentContext().projects());
            add(sb, "stageGoals", context.currentContext().stageGoals());
            add(sb, "concerns", context.currentContext().concerns());
        }
        sb.append("</trusted workbench profile>");
        return sb.toString();
    }

    private static void add(StringBuilder sb, String label, Object value) {
        if (value == null) return;
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return;
            sb.append(label).append(": ").append(String.join(", ",
                    list.stream().map(String::valueOf).toList())).append('\n');
            return;
        }
        String text = String.valueOf(value);
        if (!text.isBlank()) sb.append(label).append(": ").append(text).append('\n');
    }
}
