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
 * validação do envelope vem <b>antes</b> de aceitar: aceitar um invoke sem {@code conversationId}
 * seria o mesmo que perdê-lo — o Core não tentaria de novo.</p>
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
        if (invoke == null || invoke.tenantId() == null || invoke.tenantId().isBlank()
                || invoke.conversationId() == null || invoke.conversationId().isBlank()
                || invoke.agent() == null || invoke.agent().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "invoke exige tenantId, conversationId e agent"));
        }
        dispatcher.dispatch(invoke);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted", "agent", invoke.agent()));
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
