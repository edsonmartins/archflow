package br.com.archflow.api.web.marketplace;

import br.com.archflow.api.marketplace.MarketplaceController;
import br.com.archflow.api.marketplace.dto.ExtensionResponse;
import br.com.archflow.api.marketplace.dto.InstallExtensionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/extensions")
public class SpringMarketplaceController {

    private final MarketplaceController delegate;

    public SpringMarketplaceController(MarketplaceController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    public List<ExtensionResponse> listExtensions() { return delegate.listExtensions(); }

    @GetMapping("/{id}")
    public ExtensionResponse getExtension(@PathVariable String id) { return delegate.getExtension(id); }

    @PostMapping("/install")
    public ExtensionResponse installExtension(@RequestBody InstallExtensionRequest request) { return delegate.installExtension(request); }

    @DeleteMapping("/{id}")
    public void uninstallExtension(@PathVariable String id) { delegate.uninstallExtension(id); }

    @GetMapping("/search")
    public List<ExtensionResponse> searchExtensions(@RequestParam(defaultValue = "") String q, @RequestParam(required = false) String type) {
        return delegate.searchExtensions(q, type);
    }

    @ExceptionHandler(MarketplaceController.ExtensionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(MarketplaceController.ExtensionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
