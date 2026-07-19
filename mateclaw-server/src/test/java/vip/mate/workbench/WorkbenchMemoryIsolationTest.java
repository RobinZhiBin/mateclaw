package vip.mate.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.StructuredMemoryService;
import vip.mate.workspace.document.WorkspaceFileService;
import vip.mate.workspace.document.model.WorkspaceFileEntity;
import vip.mate.workbench.model.WorkbenchMemoryEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkbenchMemoryIsolationTest {

    @Test
    void sameVisitorAndAgentCanListConfirmedCorrectionWithParsedMetadata() {
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        when(files.getMemoryFile(7L, "structured/user.md", "api:visitor-a"))
                .thenReturn(file("""
                        ## reply_style
                        Use concise Chinese.
                        > Source: workbench-user-confirmed | Updated: 2026-07-19
                        """));
        StructuredMemoryService service = newService(files);

        List<WorkbenchMemoryEntry> entries = service.listWorkbenchEntries(
                7L, "api:visitor-a", WorkbenchMemoryService.ALLOWED_TYPES);

        assertThat(entries).containsExactly(new WorkbenchMemoryEntry(
                "user", "reply_style", "Use concise Chinese.",
                "workbench-user-confirmed", "2026-07-19"));
    }

    @Test
    void differentVisitorsAndDifferentAgentsDoNotSharePersonalMemory() {
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        when(files.getMemoryFile(7L, "structured/user.md", "api:visitor-a"))
                .thenReturn(file("""
                        ## reply_style
                        Use concise Chinese.
                        > Source: workbench-user-confirmed | Updated: 2026-07-19
                        """));
        StructuredMemoryService service = newService(files);

        assertThat(service.listWorkbenchEntries(7L, "api:visitor-b", WorkbenchMemoryService.ALLOWED_TYPES)).isEmpty();
        assertThat(service.listWorkbenchEntries(8L, "api:visitor-a", WorkbenchMemoryService.ALLOWED_TYPES)).isEmpty();
    }

    @Test
    void singleDeleteMissingDeleteAndClearUseOnlyDerivedOwnerAndAllowlistedTypes() {
        WorkspaceFileService files = mock(WorkspaceFileService.class);
        StructuredMemoryService service = newService(files);

        boolean missing = service.forget(7L, "user", "missing", "api:visitor-a");
        service.clearWorkbenchEntries(7L, "api:visitor-a", WorkbenchMemoryService.ALLOWED_TYPES);

        assertThat(missing).isFalse();
        verify(files).deleteMemoryFile(7L, "structured/user.md", "api:visitor-a");
        verify(files).deleteMemoryFile(7L, "structured/feedback.md", "api:visitor-a");
        verify(files).deleteMemoryFile(7L, "structured/project.md", "api:visitor-a");
        verify(files).deleteMemoryFile(7L, "structured/reference.md", "api:visitor-a");
    }

    private static StructuredMemoryService newService(WorkspaceFileService files) {
        return new StructuredMemoryService(files, mock(ApplicationEventPublisher.class), new MemoryProperties());
    }

    private static WorkspaceFileEntity file(String content) {
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setContent(content);
        return file;
    }
}
