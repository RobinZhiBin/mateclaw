package vip.mate.workbench.model;

public record WorkbenchCapabilityManifest(
        CapabilityState fileAnalysis,
        CapabilityState structuredDataAnalysis,
        CapabilityState authorizedFetch
) {
    public record CapabilityState(boolean available, boolean healthy, String reasonCode) {}
}
