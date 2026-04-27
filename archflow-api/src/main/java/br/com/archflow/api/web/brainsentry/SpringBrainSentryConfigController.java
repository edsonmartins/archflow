package br.com.archflow.api.web.brainsentry;

import br.com.archflow.api.brainsentry.BrainSentryConfigController;
import br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP binding for {@link BrainSentryConfigController}. */
@RestController
@RequestMapping("/api/admin/brainsentry")
public class SpringBrainSentryConfigController {

    private final BrainSentryConfigController delegate;

    public SpringBrainSentryConfigController(BrainSentryConfigController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    public BrainSentryConfigDto get() { return delegate.get(); }

    @PutMapping
    public BrainSentryConfigDto update(@RequestBody BrainSentryConfigDto update) {
        return delegate.update(update);
    }
}
