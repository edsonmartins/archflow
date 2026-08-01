package br.com.archflow.api.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStorageServiceTest {

    @TempDir
    Path directory;

    @Test
    void storesChecksumAndSanitizesFilename() {
        var service = service(1024);

        StoredFile file = service.store(
                "tenant-a", "workspace-a", "../../report.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThat(file.originalName()).isEqualTo("report.txt");
        assertThat(file.sha256())
                .isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
        assertThat(service.content("tenant-a", file.id())).asString().isEqualTo("hello");
    }

    @Test
    void tenantCannotReadListOrDeleteAnotherTenantsFile() {
        var service = service(1024);
        StoredFile file = service.store(
                "tenant-a", null, "secret.txt", "text/plain", new byte[]{1});

        assertThat(service.metadata("tenant-b", file.id())).isNull();
        assertThat(service.content("tenant-b", file.id())).isNull();
        assertThat(service.list("tenant-b", null)).isEmpty();
        assertThat(service.delete("tenant-b", file.id())).isFalse();
        assertThat(service.metadata("tenant-a", file.id())).isNotNull();
    }

    @Test
    void enforcesMaximumSizeAndEmptyFiles() {
        var service = service(2);

        assertThatThrownBy(() -> service.store(
                "tenant", null, "large", null, new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum");
        assertThatThrownBy(() -> service.store(
                "tenant", null, "empty", null, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void supportsWorkspaceFilteringAndDeletion() {
        var service = service(1024);
        StoredFile first = service.store(
                "tenant", "one", "a.txt", "text/plain", new byte[]{1});
        service.store("tenant", "two", "b.txt", "text/plain", new byte[]{2});

        assertThat(service.list("tenant", "one"))
                .extracting(StoredFile::id).containsExactly(first.id());
        assertThat(service.delete("tenant", first.id())).isTrue();
        assertThat(service.metadata("tenant", first.id())).isNull();
    }

    private FileStorageService service(long maxSize) {
        return new FileStorageService(
                new LocalFileObjectStorage(directory),
                new InMemoryFileMetadataRepository(),
                maxSize);
    }
}
