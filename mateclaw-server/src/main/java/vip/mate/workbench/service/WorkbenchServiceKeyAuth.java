package vip.mate.workbench.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.common.security.SecretEquals;

@Component
public class WorkbenchServiceKeyAuth {
    private final String configuredKey;

    public WorkbenchServiceKeyAuth(
            @Value("${mateclaw.workbench.service-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey;
    }

    public Result authenticate(String presentedKey) {
        if (configuredKey.isBlank()) return Result.UNAVAILABLE;
        if (presentedKey == null || presentedKey.isBlank()) return Result.MISSING;
        return SecretEquals.equals(configuredKey, presentedKey) ? Result.VALID : Result.INVALID;
    }

    public enum Result { VALID, MISSING, INVALID, UNAVAILABLE }
}
