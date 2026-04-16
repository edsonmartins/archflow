package br.com.archflow.api.web.events;

import br.com.archflow.api.events.EventController;
import br.com.archflow.api.events.dto.MessageRequest;
import br.com.archflow.api.events.dto.MessageResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/archflow/events")
public class SpringEventController {

    private final EventController delegate;

    public SpringEventController(EventController delegate) {
        this.delegate = delegate;
    }

    @PostMapping("/message")
    public MessageResponse sendMessage(@RequestBody MessageRequest request) {
        return delegate.sendMessage(request);
    }
}
