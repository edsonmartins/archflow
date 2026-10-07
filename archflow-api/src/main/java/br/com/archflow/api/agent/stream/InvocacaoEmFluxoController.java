package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.vendax.VendaxInvoke;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * A segunda forma de invocar um fluxo: a resposta volta em {@code text/event-stream}, na mesma
 * conexão, à medida que o modelo escreve — e fechar a conexão cancela.
 *
 * <p>A autenticação é a mesma do {@code POST /api/agents/invoke}: a chave de serviço
 * {@code archflow.vendax.invoke-key}, recusando tudo (503) enquanto ela não estiver configurada. A
 * rota liga e desliga por {@code archflow.agent.stream.enabled} (padrão: ligada).</p>
 *
 * <p>O contrato dos eventos está em {@code docs/streaming-invoke.md}.</p>
 */
@RestController
@RequestMapping("/api/agents/invoke")
@ConditionalOnProperty(name = "archflow.agent.stream.enabled", havingValue = "true",
        matchIfMissing = true)
public class InvocacaoEmFluxoController {

    private final InvocacaoEmFluxo invocacao;

    @Value("${archflow.vendax.invoke-key:}")
    private String chaveDeServico;

    public InvocacaoEmFluxoController(InvocacaoEmFluxo invocacao) {
        this.invocacao = invocacao;
    }

    @PostMapping("/stream")
    public ResponseEntity<?> invocar(
            @RequestBody VendaxInvoke invoke,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ResponseEntity<?> recusa = autenticar(authorization);
        if (recusa != null) {
            return recusa;
        }
        if (invoke == null || branco(invoke.tenantId()) || branco(invoke.agent())
                || (branco(invoke.conversationId()) && branco(invoke.idempotencyKey()))) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error",
                    "invoke exige tenantId, agent e conversationId ou idempotencyKey"));
        }
        if (invoke.definicao() == null || !invoke.definicao().eFluxo()) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error",
                    "a resposta em fluxo exige uma definição do tipo FLUXO"));
        }
        InvocacaoEmFluxo.Admissao admissao = invocacao.iniciar(invoke);
        if (admissao instanceof InvocacaoEmFluxo.Admissao.Aceita aceita) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noCache())
                    // Proxy que segura resposta (nginx) só entrega os eventos se não a armazenar.
                    .header("X-Accel-Buffering", "no")
                    .body(aceita.emitter());
        }
        InvocacaoEmFluxo.Admissao.Recusa r = (InvocacaoEmFluxo.Admissao.Recusa) admissao;
        ResponseEntity.BodyBuilder resposta = ResponseEntity.status(r.status())
                .contentType(MediaType.APPLICATION_JSON);
        if (r.retryAfterSegundos() != null) {
            resposta.header(HttpHeaders.RETRY_AFTER, String.valueOf(r.retryAfterSegundos()));
        }
        return resposta.body(Map.of("error", r.motivo()));
    }

    /**
     * Cancela por chave — para quando há proxy que não propaga o fechamento da conexão. Vale também
     * para uma invocação assíncrona em andamento. <b>É por réplica</b>: uma chave que esteja noutra
     * réplica responde 404, sem erro; fechar a conexão continua sendo o caminho principal.
     */
    @PostMapping("/{idempotencyKey}/cancel")
    public ResponseEntity<?> cancelar(
            @PathVariable String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ResponseEntity<?> recusa = autenticar(authorization);
        if (recusa != null) {
            return recusa;
        }
        return invocacao.cancelar(idempotencyKey)
                ? json(HttpStatus.ACCEPTED, Map.of("status", "cancelling"))
                : json(HttpStatus.NOT_FOUND, Map.of("error", "nenhuma execução em andamento"
                        + " com esta chave nesta instância"));
    }

    private ResponseEntity<?> autenticar(String authorization) {
        if (chaveDeServico == null || chaveDeServico.isBlank()) {
            return json(HttpStatus.SERVICE_UNAVAILABLE,
                    Map.of("error", "archflow.vendax.invoke-key não configurada"));
        }
        if (!confere(authorization)) {
            return json(HttpStatus.UNAUTHORIZED, Map.of("error", "chave de serviço inválida"));
        }
        return null;
    }

    private static ResponseEntity<Map<String, String>> json(HttpStatus status,
                                                            Map<String, String> corpo) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(corpo);
    }

    private static boolean branco(String valor) {
        return valor == null || valor.isBlank();
    }

    /** Em tempo constante: um equals comum vaza o prefixo correto pelo tempo de resposta. */
    private boolean confere(String authorization) {
        if (authorization == null) {
            return false;
        }
        String apresentada = authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : authorization.trim();
        return MessageDigest.isEqual(apresentada.getBytes(StandardCharsets.UTF_8),
                chaveDeServico.getBytes(StandardCharsets.UTF_8));
    }
}
