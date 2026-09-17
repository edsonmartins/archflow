package br.com.archflow.api.agent.vendax;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Recebe do VendaX Core a ordem de acionar um agente ({@code POST /api/agents/invoke}).
 *
 * <p>Responde <b>202</b> assim que a ordem é aceita: a execução leva dezenas de segundos e o
 * chamador é o publicador do outbox do Core, que não pode ficar bloqueado. O resultado volta pelo
 * webhook do Core, não nesta resposta.</p>
 *
 * <p>Um 202 aqui significa "vou executar", e o Core marca o evento como publicado. Por isso a
 * validação do envelope vem <b>antes</b> de aceitar: aceitar um invoke cujo resultado o Core não
 * consegue casar seria o mesmo que perdê-lo — o Core não tentaria de novo.</p>
 *
 * <p>O que casa o resultado é a {@code conversationId} ou a {@code idempotencyKey}; basta uma.
 * Até 17/09/2026 a conversa era obrigatória, e isso recusava com 400 os acionamentos que não são de
 * conversa nenhuma — a narração do dia (AP) e a consulta ao NS sem cotação aberta —, embora ambos
 * tragam a chave.</p>
 *
 * <p><b>Autenticação:</b> chave estática de serviço, no padrão do {@code /archflow/assist/} — uma
 * chamada de máquina não tem sessão para renovar um JWT de usuário. Sem chave configurada o
 * endpoint recusa tudo (503): ele está fora da proteção do filtro JWT, então abrir por omissão
 * deixaria qualquer um mandando o agente rodar.</p>
 */
@RestController
@RequestMapping("/api/agents")
public class VendaxInvokeController {

    private final VendaxAgentDispatcher dispatcher;

    @Value("${archflow.vendax.invoke-key:}")
    private String invokeKey;

    public VendaxInvokeController(VendaxAgentDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/invoke")
    public ResponseEntity<Map<String, String>> invoke(
            @RequestBody VendaxInvoke invoke,
            @RequestHeader(value = "Authorization", required = false) String authorization) {

        if (invokeKey == null || invokeKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "archflow.vendax.invoke-key não configurada"));
        }
        if (!authorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "chave de serviço inválida"));
        }
        if (invoke == null || branco(invoke.tenantId()) || branco(invoke.agent())
                || (branco(invoke.conversationId()) && branco(invoke.idempotencyKey()))) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "invoke exige tenantId, agent e conversationId ou idempotencyKey"));
        }
        try {
            dispatcher.dispatch(invoke);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "executor de agentes temporariamente saturado"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "agent", invoke.agent()));
    }

    private static boolean branco(String valor) {
        return valor == null || valor.isBlank();
    }

    /** Comparação em tempo constante: um equals comum vaza o prefixo correto pelo tempo de resposta. */
    private boolean authorized(String authorization) {
        if (authorization == null) {
            return false;
        }
        String presented = authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7).trim()
                : authorization.trim();
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                invokeKey.getBytes(StandardCharsets.UTF_8));
    }
}
