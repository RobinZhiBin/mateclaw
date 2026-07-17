package vip.mate.workbench.evidence;

import java.net.URI;
import java.util.regex.Pattern;

public record SafeSourceRef(
        String kind,
        String sourceId,
        String title,
        String locator,
        String url,
        int order
) {
    private static final Pattern SAFE_ID = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*");
    private static final Pattern SNOWFLAKE_ID = Pattern.compile("\\d{19}");
    private static final Pattern UUID_ID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public SafeSourceRef {
        if (!"wiki".equals(kind)) throw new IllegalArgumentException("Unsupported source kind");
        if (!isSafeId(sourceId)) throw new IllegalArgumentException("Unsafe source id");
        if (!isSafeMemberText(title, 160)) {
            throw new IllegalArgumentException("Invalid source title");
        }
        if (!isSafeMemberText(locator, 160)) {
            throw new IllegalArgumentException("Invalid source locator");
        }
        if (order < 1) throw new IllegalArgumentException("Invalid source order");
        if (url != null && !isPublicHttpUrl(url)) {
            throw new IllegalArgumentException("Invalid source URL");
        }
    }

    public static boolean isSafeId(String value) {
        return value != null && value.length() <= 128
                && SAFE_ID.matcher(value).matches()
                && !SNOWFLAKE_ID.matcher(value).matches()
                && !UUID_ID.matcher(value).matches();
    }

    public static boolean isSafeMemberText(String value, int maxLength) {
        if (value == null || value.length() > maxLength || value.isBlank()) return false;
        if (!value.equals(value.trim().replaceAll("\\s+", " ")) || isPathShaped(value)) return false;
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    private static boolean isPathShaped(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("/") || value.startsWith("~") || value.indexOf('\\') >= 0
                || value.matches("^[A-Za-z]:[\\\\/].*")
                || lower.startsWith("file:") || hasTraversalSegment(value) || hasUnspacedSlash(value);
    }

    private static boolean hasTraversalSegment(String value) {
        for (String segment : value.split("[/\\\\]", -1)) {
            if (".".equals(segment) || "..".equals(segment)) return true;
        }
        return false;
    }

    private static boolean hasUnspacedSlash(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '/') continue;
            boolean left = i > 0 && Character.isWhitespace(value.charAt(i - 1));
            boolean right = i + 1 < value.length() && Character.isWhitespace(value.charAt(i + 1));
            if (!left || !right) return true;
        }
        return false;
    }

    private static boolean isPublicHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
