package vip.mate.workbench.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vip.mate.common.security.SecretEquals;

@Slf4j
@Component
public class WorkbenchServiceKeyAuth implements ApplicationRunner {
    private final String configuredKey;

    public WorkbenchServiceKeyAuth(
            @Value("${mateclaw.workbench.service-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (configuredKey.isBlank()) {
            log.error("MATECLAW_WORKBENCH_SERVICE_KEY is blank; set it and restart MateClaw; "
                    + "capability requests remain fail closed with HTTP 503.");
        }
    }

    public Result authenticate(String presentedKey) {
        if (configuredKey.isBlank()) return Result.UNAVAILABLE;
        if (presentedKey == null || presentedKey.isBlank()) return Result.MISSING;
        return SecretEquals.equals(configuredKey, presentedKey) ? Result.VALID : Result.INVALID;
    }

    public enum Result { VALID, MISSING, INVALID, UNAVAILABLE }
}
