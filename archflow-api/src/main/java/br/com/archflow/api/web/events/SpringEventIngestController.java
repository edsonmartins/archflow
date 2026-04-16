package br.com.archflow.api.web.events;

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

    @PostMapping(value = "/ingest", consumes = "application/x-protobuf")
    public IngestResultDto ingest(
            @RequestBody byte[] protobufBody,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {
        return delegate.ingest(protobufBody, tenantId);
    }
}
