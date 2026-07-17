package vip.mate.workbench.evidence;

import vip.mate.agent.graph.state.SourceEvidenceLedger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SafeSourceRefProjector {
    private SafeSourceRefProjector() {}

    public static List<SafeSourceRef> from(SourceEvidenceLedger ledger) {
        if (ledger == null || ledger.wikiCitations() == null) return List.of();
        List<SourceEvidenceLedger.WikiCitation> citations = ledger.wikiCitations().stream()
                .sorted(Comparator.comparingInt(SourceEvidenceLedger.WikiCitation::index))
                .toList();
        List<SafeSourceRef> refs = new ArrayList<>();
        for (SourceEvidenceLedger.WikiCitation citation : citations) {
            String title = normalizeText(citation.title(), 160);
            if (!SafeSourceRef.isSafeMemberText(title, 160)) continue;
            String slug = citation.slug() == null ? "" : citation.slug().trim();
            boolean hasSafeSlug = SafeSourceRef.isSafeId(slug);
            String locator = locator(citation, hasSafeSlug);
            if (!SafeSourceRef.isSafeMemberText(locator, 160)) continue;
            int order = refs.size() + 1;
            String sourceId = hasSafeSlug ? slug : "wiki-citation-" + order;
            refs.add(new SafeSourceRef("wiki", sourceId, title, locator, null, order));
        }
        return List.copyOf(refs);
    }

    private static String locator(SourceEvidenceLedger.WikiCitation citation, boolean hasSafeSlug) {
        List<String> parts = new ArrayList<>(2);
        String section = normalizeText(citation.section(), 120);
        if (SafeSourceRef.isSafeMemberText(section, 120)) parts.add(section);
        Integer page = citation.pageNumber();
        if (page != null && page > 0 && page <= 100_000) parts.add("page " + page);
        if (parts.isEmpty() && hasSafeSlug) parts.add("page");
        return String.join(" · ", parts);
    }

    private static String normalizeText(String value, int maxLength) {
        if (value == null) return "";
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.CONTROL && type != Character.FORMAT) safe.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        String normalized = safe.toString().trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength).trim();
    }

}
