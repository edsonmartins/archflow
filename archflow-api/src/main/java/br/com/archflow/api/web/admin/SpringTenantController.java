package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.TenantController;
import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.TenantDto.CreateTenantRequest;
import br.com.archflow.api.admin.dto.TenantDto.TenantStatsDto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tenants")
public class SpringTenantController {

    private final TenantController delegate;

    public SpringTenantController(TenantController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    public List<TenantDto> listTenants() { return delegate.listTenants(); }

    @GetMapping("/stats")
    public TenantStatsDto getStats() { return delegate.getStats(); }

    @PostMapping
    public TenantDto createTenant(@RequestBody CreateTenantRequest request) { return delegate.createTenant(request); }

    @GetMapping("/{id}")
    public TenantDto getTenant(@PathVariable String id) { return delegate.getTenant(id); }

    @PutMapping("/{id}")
    public TenantDto updateTenant(@PathVariable String id, @RequestBody TenantDto update) { return delegate.updateTenant(id, update); }

    @PostMapping("/{id}/suspend")
    public void suspendTenant(@PathVariable String id) { delegate.suspendTenant(id); }

    @PostMapping("/{id}/activate")
    public void activateTenant(@PathVariable String id) { delegate.activateTenant(id); }

    @DeleteMapping("/{id}")
    public void deleteTenant(@PathVariable String id) { delegate.deleteTenant(id); }
}
