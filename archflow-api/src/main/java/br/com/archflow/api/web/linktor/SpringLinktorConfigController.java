package br.com.archflow.api.web.linktor;

import br.com.archflow.api.linktor.LinktorConfigController;
import br.com.archflow.api.linktor.dto.LinktorConfigDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP binding for {@link LinktorConfigController}. */
@RestController
@RequestMapping("/api/admin/linktor")
public class SpringLinktorConfigController {

    private final LinktorConfigController delegate;

    public SpringLinktorConfigController(LinktorConfigController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    public LinktorConfigDto get() { return delegate.get(); }

    @PutMapping
    public LinktorConfigDto update(@RequestBody LinktorConfigDto update) {
        return delegate.update(update);
    }
}
