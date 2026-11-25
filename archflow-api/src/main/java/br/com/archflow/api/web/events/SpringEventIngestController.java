package br.com.archflow.api.web.events;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.events.ingest.EventIngestController;
import br.com.archflow.api.events.ingest.EventIngestController.IngestResultDto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
public class SpringEventIngestController {

    private final EventIngestController delegate;

    public SpringEventIngestController(EventIngestController delegate) {
        this.delegate = delegate;
    }

    /**
     * O tenant que "fixa" os eventos vem do atributo resolvido pelo {@link ImpersonationFilter}, não
     * do header cru: o filtro só deixa o {@code X-Tenant-Id} valer quando não há JWT, e o recusa
     * quando diverge do tenant do token. Lendo o header direto, um agente autenticado como tenant A
     * publicava no canal admin do tenant B (RCHFLW-44). Com JWT o atributo nunca é nulo (token sem
     * claim = {@code __default__}), então os eventos de um agente autenticado são sempre fixados no
     * tenant dele. Só sem JWT e sem header continua {@code null}, como antes — o ingest então não
     * fixa tenant nem publica no canal admin. O outro caminho para o canal de B — um
     * {@code executionId} no corpo com a forma {@code "<B>:..."} ou {@code "__admin__:<B>"} — é
     * recusado em {@code EventIngestControllerImpl.canalPermitido}.
     */
    @PostMapping(value = "/ingest", consumes = "application/x-protobuf")
    public IngestResultDto ingest(
            @RequestBody byte[] protobufBody,
            @RequestAttribute(value = ImpersonationFilter.ATTR_TENANT_ID, required = false) String tenantId) {
        return delegate.ingest(protobufBody, tenantId);
    }
}
