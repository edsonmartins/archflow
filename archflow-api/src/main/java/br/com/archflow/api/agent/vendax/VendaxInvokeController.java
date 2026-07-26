package br.com.archflow.api.agent.vendax;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Recebe do VendaX Core a ordem de acionar um agente ({@code POST /api/agents/invoke}).
 *
 * <p>Responde <b>202</b> assim que a ordem é aceita: a execução leva dezenas de segundos e o
 * chamador é o publicador do outbox do Core, que não pode ficar bloqueado. O resultado volta pelo
 * webhook do Core, não nesta resposta.</p>
 *
 * <p>Um 202 aqui significa "vou executar", e o Core marca o evento como publicado. Por isso a
 * validação do envelope é feita <b>antes</b> de aceitar: aceitar um invoke sem
 * {@code conversationId} seria o mesmo que perdê-lo — o Core não tentaria de novo.</p>
 */
@RestController
@RequestMapping("/api/agents")
public class VendaxInvokeController {

    private final VendaxAgentDispatcher dispatcher;

    public VendaxInvokeController(VendaxAgentDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping("/invoke")
    public ResponseEntity<Map<String, String>> invoke(@RequestBody VendaxInvoke invoke) {
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
}
