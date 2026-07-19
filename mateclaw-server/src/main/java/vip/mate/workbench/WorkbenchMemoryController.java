package vip.mate.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.workbench.service.WorkbenchServiceKeyAuth;

@RestController
@RequestMapping("/api/v1/workbench/agents/{agentId}/visitors/{visitorId}/memories")
public class WorkbenchMemoryController {

    private final WorkbenchServiceKeyAuth serviceKeyAuth;
    private final WorkbenchMemoryService memoryService;

    public WorkbenchMemoryController(WorkbenchServiceKeyAuth serviceKeyAuth, WorkbenchMemoryService memoryService) {
        this.serviceKeyAuth = serviceKeyAuth;
        this.memoryService = memoryService;
    }

    @GetMapping
    public ResponseEntity<R<?>> list(
            @PathVariable Long agentId,
            @PathVariable String visitorId,
            @RequestHeader(value = "X-Workbench-Service-Key", required = false) String serviceKey,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceHeader) {
        ResponseEntity<R<?>> authFailure = authFailure(serviceKey);
        if (authFailure != null) return authFailure;

        Long workspaceId = positiveLong(workspaceHeader);
        if (workspaceId == null) return error(403, "Forbidden", "WORKBENCH_AGENT_FORBIDDEN");
        try {
            return memoryService.list(agentId, workspaceId, visitorId)
                    .<ResponseEntity<R<?>>>map(WorkbenchMemoryController::success)
                    .orElseGet(() -> error(404, "Not found", "WORKBENCH_AGENT_NOT_FOUND"));
        } catch (IllegalArgumentException e) {
            return error(400, "Bad request", "WORKBENCH_MEMORY_BAD_REQUEST");
        }
    }

    @PutMapping("/{type}/{key}")
    public ResponseEntity<R<?>> remember(
            @PathVariable Long agentId,
            @PathVariable String visitorId,
            @PathVariable String type,
            @PathVariable String key,
            @RequestHeader(value = "X-Workbench-Service-Key", required = false) String serviceKey,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceHeader,
            @RequestBody JsonNode request) {
        ResponseEntity<R<?>> authFailure = authFailure(serviceKey);
        if (authFailure != null) return authFailure;

        Long workspaceId = positiveLong(workspaceHeader);
        if (workspaceId == null) return error(403, "Forbidden", "WORKBENCH_AGENT_FORBIDDEN");
        try {
            return memoryService.remember(agentId, workspaceId, visitorId, type, key, requestContent(request))
                    .<ResponseEntity<R<?>>>map(WorkbenchMemoryController::success)
                    .orElseGet(() -> error(404, "Not found", "WORKBENCH_AGENT_NOT_FOUND"));
        } catch (IllegalArgumentException e) {
            return error(400, "Bad request", "WORKBENCH_MEMORY_BAD_REQUEST");
        }
    }

    @DeleteMapping("/{type}/{key}")
    public ResponseEntity<R<?>> forget(
            @PathVariable Long agentId,
            @PathVariable String visitorId,
            @PathVariable String type,
            @PathVariable String key,
            @RequestHeader(value = "X-Workbench-Service-Key", required = false) String serviceKey,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceHeader) {
        ResponseEntity<R<?>> authFailure = authFailure(serviceKey);
        if (authFailure != null) return authFailure;

        Long workspaceId = positiveLong(workspaceHeader);
        if (workspaceId == null) return error(403, "Forbidden", "WORKBENCH_AGENT_FORBIDDEN");
        try {
            return memoryService.forget(agentId, workspaceId, visitorId, type, key)
                    .<ResponseEntity<R<?>>>map(deleted -> success(new DeleteResult(deleted)))
                    .orElseGet(() -> error(404, "Not found", "WORKBENCH_AGENT_NOT_FOUND"));
        } catch (IllegalArgumentException e) {
            return error(400, "Bad request", "WORKBENCH_MEMORY_BAD_REQUEST");
        }
    }

    @DeleteMapping
    public ResponseEntity<R<?>> clear(
            @PathVariable Long agentId,
            @PathVariable String visitorId,
            @RequestHeader(value = "X-Workbench-Service-Key", required = false) String serviceKey,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceHeader) {
        ResponseEntity<R<?>> authFailure = authFailure(serviceKey);
        if (authFailure != null) return authFailure;

        Long workspaceId = positiveLong(workspaceHeader);
        if (workspaceId == null) return error(403, "Forbidden", "WORKBENCH_AGENT_FORBIDDEN");
        try {
            return memoryService.clear(agentId, workspaceId, visitorId)
                    .<ResponseEntity<R<?>>>map(clearedTypes -> success(new ClearResult(clearedTypes)))
                    .orElseGet(() -> error(404, "Not found", "WORKBENCH_AGENT_NOT_FOUND"));
        } catch (IllegalArgumentException e) {
            return error(400, "Bad request", "WORKBENCH_MEMORY_BAD_REQUEST");
        }
    }

    private ResponseEntity<R<?>> authFailure(String serviceKey) {
        WorkbenchServiceKeyAuth.Result auth = serviceKeyAuth.authenticate(serviceKey);
        if (auth == WorkbenchServiceKeyAuth.Result.UNAVAILABLE) {
            return error(503, "Service unavailable", "WORKBENCH_SERVICE_FORBIDDEN");
        }
        if (auth == WorkbenchServiceKeyAuth.Result.MISSING) {
            return error(401, "Authentication required", "WORKBENCH_SERVICE_AUTH_REQUIRED");
        }
        if (auth == WorkbenchServiceKeyAuth.Result.INVALID) {
            return error(403, "Forbidden", "WORKBENCH_SERVICE_FORBIDDEN");
        }
        return null;
    }

    private static Long positiveLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static ResponseEntity<R<?>> error(int status, String message, String errorCode) {
        R<FailureData> body = new R<>();
        body.setCode(status);
        body.setMsg(message);
        body.setData(new FailureData(errorCode, false));
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<R<?>> success(Object data) {
        R<Object> body = new R<>();
        body.setCode(0);
        body.setMsg("ok");
        body.setData(data);
        return ResponseEntity.ok(body);
    }

    private static String requestContent(JsonNode request) {
        if (request == null
                || !request.isObject()
                || request.size() != 1
                || !request.has("content")
                || !request.get("content").isTextual()) {
            throw new IllegalArgumentException("request must contain only a textual content field");
        }
        return request.get("content").textValue();
    }

    public record DeleteResult(boolean deleted) {}
    public record ClearResult(int clearedTypes) {}
    public record FailureData(String errorCode, boolean retryable) {}
}
