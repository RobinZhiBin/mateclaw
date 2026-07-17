package vip.mate.workbench.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.workbench.model.WorkbenchCapabilityManifest;
import vip.mate.workbench.service.WorkbenchCapabilityService;
import vip.mate.workbench.service.WorkbenchServiceKeyAuth;

@RestController
@RequestMapping("/api/v1/workbench/agents")
public class WorkbenchCapabilityController {
    private final WorkbenchServiceKeyAuth serviceKeyAuth;
    private final WorkbenchCapabilityService capabilityService;

    public WorkbenchCapabilityController(
            WorkbenchServiceKeyAuth serviceKeyAuth,
            WorkbenchCapabilityService capabilityService) {
        this.serviceKeyAuth = serviceKeyAuth;
        this.capabilityService = capabilityService;
    }

    @GetMapping("/{agentId}/capabilities")
    public ResponseEntity<R<?>> capabilities(
            @PathVariable Long agentId,
            @RequestHeader(value = "X-Workbench-Service-Key", required = false) String serviceKey,
            @RequestHeader(value = "X-Workspace-Id", required = false) String workspaceHeader) {
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

        Long workspaceId = positiveLong(workspaceHeader);
        if (workspaceId == null) return error(403, "Forbidden", "WORKBENCH_AGENT_FORBIDDEN");
        return capabilityService.resolve(agentId, workspaceId)
                .<ResponseEntity<R<?>>>map(WorkbenchCapabilityController::success)
                .orElseGet(() -> error(404, "Not found", "WORKBENCH_AGENT_NOT_FOUND"));
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

    private static ResponseEntity<R<?>> success(WorkbenchCapabilityManifest manifest) {
        R<WorkbenchCapabilityManifest> body = new R<>();
        body.setCode(0);
        body.setMsg("ok");
        body.setData(manifest);
        return ResponseEntity.ok(body);
    }

    public record FailureData(String errorCode, boolean retryable) {}
}
