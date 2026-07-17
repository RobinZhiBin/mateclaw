package vip.mate.workbench.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@ExtendWith(OutputCaptureExtension.class)
class WorkbenchServiceKeyAuthTest {

    @Test
    void blankConfigurationEmitsOneFixedOperatorError(CapturedOutput output) throws Exception {
        WorkbenchServiceKeyAuth auth = new WorkbenchServiceKeyAuth("   ");

        auth.run(mock(ApplicationArguments.class));

        assertThat(output.getOut() + output.getErr())
                .containsOnlyOnce("MATECLAW_WORKBENCH_SERVICE_KEY is blank")
                .contains("ERROR")
                .contains("HTTP 503");
        assertThat(auth.authenticate("any-presented-key"))
                .isEqualTo(WorkbenchServiceKeyAuth.Result.UNAVAILABLE);
    }

    @Test
    void configuredKeyDoesNotEmitConfigurationErrorOrSecret(CapturedOutput output) throws Exception {
        String sentinel = "never-log-this-service-key";
        WorkbenchServiceKeyAuth auth = new WorkbenchServiceKeyAuth(sentinel);

        auth.run(mock(ApplicationArguments.class));

        assertThat(output.getOut() + output.getErr())
                .doesNotContain("MATECLAW_WORKBENCH_SERVICE_KEY is blank")
                .doesNotContain(sentinel);
        assertThat(auth.authenticate(sentinel)).isEqualTo(WorkbenchServiceKeyAuth.Result.VALID);
    }
}
