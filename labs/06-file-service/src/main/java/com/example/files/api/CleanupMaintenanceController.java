package com.example.files.api;

import com.example.files.api.security.*;
import com.example.files.application.cleanup.CleanupTaskRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;

/** 仅用于受控维护环境的失败任务重置接口；请求体不携带资源状态或 object key。 */
@RestController
@RequestMapping("/api/admin/storage-cleanups")
@Profile({"local", "test"})
@ConditionalOnProperty(prefix = "file.maintenance", name = "enabled", havingValue = "true")
@ConditionalOnBean(RequesterIdentityResolver.class)
public final class CleanupMaintenanceController {
    private final CleanupTaskRepository tasks;
    private final RequesterIdentityResolver identities;
    public CleanupMaintenanceController(CleanupTaskRepository tasks, RequesterIdentityResolver identities) {
        this.tasks = java.util.Objects.requireNonNull(tasks, "tasks");
        this.identities = java.util.Objects.requireNonNull(identities, "identities");
    }
    @PostMapping("/{taskId}/retry")
    public void retry(@PathVariable UUID taskId, HttpServletRequest request) {
        if (identities.resolve(request).isEmpty()) throw new RequesterUnauthenticatedException();
        if (!tasks.resetFailed(taskId)) throw new IllegalStateException("cleanup task is not failed");
    }
}
