package br.com.archflow.api.web.approval;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/archflow/approvals", "/api/approvals"})
public class SpringApprovalController {

    private final ApprovalController delegate;

    public SpringApprovalController(ApprovalController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/pending/count")
    public Map<String, Integer> pendingCount(@RequestParam(required = false) String tenantId) {
        return Map.of("count", delegate.listPending(tenantId).size());
    }

    @PostMapping("/{requestId}")
    public ApprovalResponse submitDecision(@PathVariable String requestId, @RequestBody ApprovalSubmitRequest request) {
        return delegate.submitDecision(requestId, request);
    }

    @GetMapping("/pending")
    public List<ApprovalResponse> listPending(@RequestParam(required = false) String tenantId) {
        return delegate.listPending(tenantId);
    }

    @GetMapping("/{requestId}")
    public ApprovalResponse getApproval(@PathVariable String requestId) {
        return delegate.getApproval(requestId);
    }
}
