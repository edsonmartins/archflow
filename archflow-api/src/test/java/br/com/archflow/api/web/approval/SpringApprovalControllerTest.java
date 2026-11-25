package br.com.archflow.api.web.approval;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.config.TenantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O decisor de uma aprovação é quem está autenticado, nunca o que o corpo diz (RCHFLW-38).
 *
 * <p>Antes, o {@code responderId} do corpo ia direto para {@code APPROVAL_DECIDED_BY}: a UI mandava
 * sempre "me" e qualquer usuário autenticado gravava a decisão em nome de outro — o estado durável
 * respondia "quem autorizou isto" com o que o cliente quis.</p>
 */
@DisplayName("SpringApprovalController — decisor vem do token")
class SpringApprovalControllerTest {

    private final AtomicReference<ApprovalSubmitRequest> received = new AtomicReference<>();
    private final AtomicReference<String> lookedUpFlow = new AtomicReference<>();
    private SpringApprovalController controller;

    @BeforeEach
    void setUp() {
        ApprovalController delegate = new ApprovalController() {
            @Override
            public ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request) {
                received.set(request);
                return new ApprovalResponse(requestId, "acme", "flow-1", "gate", request.decision(),
                        null, null, null, null);
            }

            @Override
            public List<ApprovalResponse> listPending(String tenantId) {
                return List.of();
            }

            @Override
            public ApprovalResponse getApproval(String requestId) {
                return null;
            }

            @Override
            public ApprovalResponse getApproval(String requestId, String flowId) {
                lookedUpFlow.set(flowId);
                return null;
            }

            @Override
            public ApprovalResponse getApproval(String requestId, String flowId, String tenantScope) {
                return getApproval(requestId, flowId);
            }

            @Override
            public ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request,
                                                   String tenantScope) {
                return submitDecision(requestId, request);
            }
        };
        // Auth desligada: aqui o assunto é o decisor; o recorte de tenant tem teste próprio.
        controller = new SpringApprovalController(delegate, new TenantScope(false));
    }

    private static ApprovalSubmitRequest bodyClaiming(String responderId) {
        return new ApprovalSubmitRequest("acme", "APPROVED", null, responderId, "ok");
    }

    @Test
    @DisplayName("o nome de usuário do token vence o responderId do corpo")
    void usernameFromTokenWinsOverBody() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "u-42");
        http.setAttribute(JwtAuthenticationFilter.ATTR_USERNAME, "maria");

        controller.submitDecision("req-1", bodyClaiming("diretor.financeiro"), http);

        assertThat(received.get().responderId()).isEqualTo("maria");
        // O resto do pedido passa intacto
        assertThat(received.get().decision()).isEqualTo("APPROVED");
        assertThat(received.get().comment()).isEqualTo("ok");
        assertThat(received.get().tenantId()).isEqualTo("acme");
    }

    @Test
    @DisplayName("sem nome de usuário no token, vale o subject")
    void subjectWhenNoUsername() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "u-42");

        controller.submitDecision("req-1", bodyClaiming("me"), http);

        assertThat(received.get().responderId()).isEqualTo("u-42");
    }

    @Test
    @DisplayName("sem token (auth desligada, dev): marcador explícito, nunca o nome do corpo")
    void anonymousMarkerWithoutToken() {
        controller.submitDecision("req-1", bodyClaiming("diretor.financeiro"), new MockHttpServletRequest());

        assertThat(received.get().responderId())
                .isEqualTo(SpringApprovalController.ANONYMOUS_DECIDER)
                .isNotEqualTo("diretor.financeiro");
    }

    @Test
    @DisplayName("GET do detalhe repassa a execução: é ela que acha um pedido já decidido")
    void detailForwardsTheExecution() {
        controller.getApproval("req-1", "exec-1", null);

        assertThat(lookedUpFlow.get()).isEqualTo("exec-1");
    }
}
