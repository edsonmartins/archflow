package br.com.archflow.api.approval.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-188, item 18: o motivo de uma recusa ou expiração aparecia em inglês na UI em pt-BR, com o id cru do passo e
 * a duração ISO ("Rejected at approval gate 'ap': no decision within PT2M (approval timeout)").
 */
@DisplayName("Motivo do encerramento por recusa: o portão e o prazo legíveis")
class MotivoDoEncerramentoTest {

    @Test
    @DisplayName("o prazo sai como o usuário o lê, não em ISO-8601")
    void prazoLegivel() {
        assertThat(ApprovalQueueService.prazoLegivel(Duration.parse("PT2M"))).isEqualTo("2 min");
        assertThat(ApprovalQueueService.prazoLegivel(Duration.parse("PT1H30M"))).isEqualTo("1 h 30 min");
        assertThat(ApprovalQueueService.prazoLegivel(Duration.parse("PT45S"))).isEqualTo("45 s");
        assertThat(ApprovalQueueService.prazoLegivel(Duration.parse("P1D"))).isEqualTo("24 h");
    }

    @Test
    @DisplayName("o portão pelo título que o aprovador viu, com o id entre parênteses; sem título, o id")
    void nomeDoPortao() {
        assertThat(ApprovalQueueService.nomeDoPortao("Reembolso acima de R$ 500", "ap"))
                .isEqualTo("«Reembolso acima de R$ 500» (ap)");
        assertThat(ApprovalQueueService.nomeDoPortao(null, "ap")).isEqualTo("«ap»");
        assertThat(ApprovalQueueService.nomeDoPortao(" ", null)).isEqualTo("do fluxo");
    }
}
