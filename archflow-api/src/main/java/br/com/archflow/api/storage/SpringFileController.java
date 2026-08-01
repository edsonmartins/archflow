package br.com.archflow.api.storage;

import br.com.archflow.api.config.TenantContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Tenant-scoped upload, metadata, download and deletion endpoints. */
@RestController
@RequestMapping("/api/files")
public class SpringFileController {

    private final FileStorageService service;

    public SpringFileController(FileStorageService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<StoredFile> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String workspaceId) throws IOException {
        StoredFile stored = service.store(
                TenantContext.currentTenantId(),
                workspaceId,
                file.getOriginalFilename(),
                file.getContentType(),
                file.getBytes());
        return ResponseEntity.status(201).body(stored);
    }

    @GetMapping
    public List<StoredFile> list(@RequestParam(required = false) String workspaceId) {
        return service.list(TenantContext.currentTenantId(), workspaceId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoredFile> metadata(@PathVariable String id) {
        StoredFile file = service.metadata(TenantContext.currentTenantId(), id);
        return file == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(file);
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable String id) {
        String tenantId = TenantContext.currentTenantId();
        StoredFile file = service.metadata(tenantId, id);
        if (file == null) return ResponseEntity.notFound().build();
        byte[] content = service.content(tenantId, id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.contentType()));
        headers.setContentLength(content.length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.originalName(), StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(content);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(TenantContext.currentTenantId(), id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
