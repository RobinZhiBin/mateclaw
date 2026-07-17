package vip.mate.workbench;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.tool.service.ToolService;
import vip.mate.workbench.model.WorkbenchCapabilityManifest;
import vip.mate.workbench.service.WorkbenchCapabilityService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:workbench_capability_runtime_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.feature-flag.refresh-ms=999999"
})
class WorkbenchCapabilityRuntimeNamesIntegrationTest {

    private static final AtomicLong AGENT_IDS = new AtomicLong(9_400_000L);

    @Autowired private AgentBindingService bindingService;
    @Autowired private AvailableToolService availableToolService;
    @Autowired private ToolService toolService;
    @Autowired private WorkbenchCapabilityService capabilityService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long agentId;

    @BeforeEach
    void setUp() {
        agentId = AGENT_IDS.getAndIncrement();
        jdbcTemplate.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, "
                        + "workspace_id, create_time, update_time, deleted) "
                        + "KEY(id) VALUES (?, ?, 'plan_execute', '', 10, TRUE, 1, "
                        + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                agentId, "workbench-capability-agent-" + agentId);
        bindingService.bindTool(agentId, "delegateToAgent");
    }

    @Test
    void reportsFileAnalysisAvailableFromSeededBuiltinRuntimeFunctions() {
        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        List<ToolEntity> seededTools = toolService.listEnabledTools();

        assertThat(effective).contains(
                "read_file", "extract_document_text", "extract_pdf_text", "extract_docx_text");
        assertThat(seededTools).anySatisfy(tool -> {
            assertThat(tool.getName()).isEqualTo("ReadFileTool");
            assertThat(tool.getRuntimeNames()).contains("read_file");
        });
        assertThat(seededTools).anySatisfy(tool -> {
            assertThat(tool.getName()).isEqualTo("DocumentExtractTool");
            assertThat(tool.getRuntimeNames()).contains(
                    "extract_document_text", "extract_pdf_text", "extract_docx_text");
        });
        assertThat(availableToolService.listAvailable())
                .extracting("name")
                .contains("ReadFileTool", "DocumentExtractTool");

        WorkbenchCapabilityManifest manifest = capabilityService.resolve(agentId, 1L).orElseThrow();

        assertThat(manifest.fileAnalysis().available()).isTrue();
        assertThat(manifest.fileAnalysis().healthy()).isTrue();
        assertThat(manifest.fileAnalysis().reasonCode()).isEqualTo("AVAILABLE");
    }
}
