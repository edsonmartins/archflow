package br.com.archflow.api.web.approval;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/archflow/approvals")
public class SpringApprovalController {

    private final ApprovalController delegate;

    public SpringApprovalController(ApprovalController delegate) {
        this.delegate = delegate;
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
