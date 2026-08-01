package br.com.archflow.api.jobs;

import br.com.archflow.api.config.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Public tenant-scoped job submission and monitoring API. */
@RestController
@RequestMapping("/api/jobs")
public class SpringJobController {

    private final JobService service;

    public SpringJobController(JobService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<JobRecord> submit(@RequestBody SubmitJobRequest request) {
        JobRecord job = service.submit(TenantContext.currentTenantId(), request.workspaceId(),
                request.type(), request.payload(), request.idempotencyKey(),
                request.maxAttempts() == null ? 3 : request.maxAttempts());
        return ResponseEntity.status(202).body(job);
    }

    @GetMapping
    public List<JobRecord> list(@RequestParam(required = false) String type) {
        return service.list(TenantContext.currentTenantId(), type);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobRecord> get(@PathVariable String id) {
        JobRecord job = service.get(TenantContext.currentTenantId(), id);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<JobRecord> cancel(@PathVariable String id) {
        JobRecord job = service.cancel(TenantContext.currentTenantId(), id);
        return job == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(job);
    }

    @GetMapping("/{id}/attempts")
    public ResponseEntity<List<JobAttempt>> attempts(@PathVariable String id) {
        String tenantId = TenantContext.currentTenantId();
        if (service.get(tenantId, id) == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(service.attempts(tenantId, id));
    }

    public record SubmitJobRequest(
            String type,
            String workspaceId,
            Map<String, Object> payload,
            String idempotencyKey,
            Integer maxAttempts) {
    }
}
