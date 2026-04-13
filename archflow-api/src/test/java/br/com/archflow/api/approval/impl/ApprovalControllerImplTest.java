package br.com.archflow.api.approval.impl;

import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalControllerImpl")
class ApprovalControllerImplTest {

    @Mock
    ApprovalQueueService service;

    ApprovalControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new ApprovalControllerImpl(service);
    }

    private ApprovalResponse sampleResponse(String id, String status) {
        return new ApprovalResponse(
                id,
                "tenant-1",
                "flow-1",
                "step-1",
                status,
                "Approve the refund",
                "payload",
                Instant.now(),
                Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("constructor rejects null service")
    void rejectsNullService() {
        assertThatThrownBy(() -> new ApprovalControllerImpl(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("submitDecision delegates to the queue service")
    void submitDelegates() {
        ApprovalSubmitRequest request = new ApprovalSubmitRequest(
                "tenant-1", "APPROVED", null, "user-1");
        ApprovalResponse expected = sampleResponse("req-1", "APPROVED");
        when(service.submitDecision("req-1", request)).thenReturn(expected);

        assertThat(controller.submitDecision("req-1", request)).isSameAs(expected);
        verify(service).submitDecision("req-1", request);
    }

    @Test
    @DisplayName("listPending delegates to the queue service")
    void listPendingDelegates() {
        List<ApprovalResponse> expected = List.of(sampleResponse("req-1", "PENDING"));
        when(service.listPending("tenant-1")).thenReturn(expected);

        assertThat(controller.listPending("tenant-1")).isSameAs(expected);
        verify(service).listPending("tenant-1");
    }

    @Test
    @DisplayName("getApproval delegates to service.getDetail")
    void getApprovalDelegates() {
        ApprovalResponse expected = sampleResponse("req-1", "PENDING");
        when(service.getDetail("req-1")).thenReturn(expected);

        assertThat(controller.getApproval("req-1")).isSameAs(expected);
        verify(service).getDetail("req-1");
    }

    @Test
    @DisplayName("exceptions from the service propagate unchanged")
    void propagatesExceptions() {
        when(service.getDetail("nope")).thenThrow(new java.util.NoSuchElementException("not found"));
        assertThatThrownBy(() -> controller.getApproval("nope"))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
