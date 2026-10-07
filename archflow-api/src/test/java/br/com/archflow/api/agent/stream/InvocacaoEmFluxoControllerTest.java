package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.vendax.DefinicaoDeAgente;
import br.com.archflow.api.agent.vendax.VendaxInvoke;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("rota de resposta em fluxo")
class InvocacaoEmFluxoControllerTest {

    private final InvocacaoEmFluxo invocacao = mock(InvocacaoEmFluxo.class);
    private final InvocacaoEmFluxoController controller = new InvocacaoEmFluxoController(invocacao);

    private static VendaxInvoke invoke(DefinicaoDeAgente definicao) {
        return new VendaxInvoke("1.0", "t1", "c1", "assistente", "m1", "olá", "LIGHT", "teste",
                "trace", null, null, null, "k1", null, definicao);
    }

    private static DefinicaoDeAgente fluxo() {
        return new DefinicaoDeAgente("FLUXO", Map.of("steps", List.of()), null, null,
                List.of(), Map.of(), Map.of(), "demo@1");
    }

    private void comChave(String chave) {
        ReflectionTestUtils.setField(controller, "chaveDeServico", chave);
    }

    @Test
    @DisplayName("sem chave de serviço configurada, recusa com 503 — como o invoke assíncrono")
    void semChaveConfigurada() {
        comChave("");
        assertThat(controller.invocar(invoke(fluxo()), "Bearer x").getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(invocacao, never()).iniciar(any());
    }

    @Test
    @DisplayName("chave errada: 401; sem chave: 401")
    void chaveErrada() {
        comChave("segredo");
        assertThat(controller.invocar(invoke(fluxo()), "Bearer outra").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.invocar(invoke(fluxo()), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.cancelar("k1", "Bearer outra").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("sem definição do tipo FLUXO: 400")
    void exigeFluxo() {
        comChave("segredo");
        assertThat(controller.invocar(invoke(null), "Bearer segredo").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("aceita: 200 text/event-stream, sem cache e sem buffer de proxy")
    void aceita() {
        comChave("segredo");
        when(invocacao.iniciar(any())).thenReturn(
                new InvocacaoEmFluxo.Admissao.Aceita(new SseEmitter(0L)));

        ResponseEntity<?> r = controller.invocar(invoke(fluxo()), "Bearer segredo");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getContentType().toString()).startsWith("text/event-stream");
        assertThat(r.getHeaders().getCacheControl()).contains("no-cache");
        assertThat(r.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    @DisplayName("capacidade esgotada: 429 com Retry-After; chave em andamento: 409")
    void recusas() {
        comChave("segredo");
        when(invocacao.iniciar(any()))
                .thenReturn(new InvocacaoEmFluxo.Admissao.Recusa(429, "cheio", 7))
                .thenReturn(new InvocacaoEmFluxo.Admissao.Recusa(409, "em andamento", null));

        ResponseEntity<?> cheio = controller.invocar(invoke(fluxo()), "Bearer segredo");
        assertThat(cheio.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(cheio.getHeaders().getFirst("Retry-After")).isEqualTo("7");

        ResponseEntity<?> emAndamento = controller.invocar(invoke(fluxo()), "Bearer segredo");
        assertThat(emAndamento.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(emAndamento.getHeaders().getFirst("Retry-After")).isNull();
    }

    @Test
    @DisplayName("cancelar: 202 quando a chave está aqui; 404 quando não está (outra réplica)")
    void cancelar() {
        comChave("segredo");
        when(invocacao.cancelar("aqui")).thenReturn(true);
        when(invocacao.cancelar("longe")).thenReturn(false);

        assertThat(controller.cancelar("aqui", "Bearer segredo").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(controller.cancelar("longe", "Bearer segredo").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
