package vip.mate.workbench.model;

public record WorkbenchMemoryEntry(
        String type,
        String key,
        String content,
        String source,
        String updatedAt) {
}
