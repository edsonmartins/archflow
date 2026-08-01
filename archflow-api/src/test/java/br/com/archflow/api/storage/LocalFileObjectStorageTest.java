package br.com.archflow.api.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileObjectStorageTest {

    @TempDir
    Path directory;

    @Test
    void roundTripAndDelete() {
        var storage = new LocalFileObjectStorage(directory);

        storage.put("ab/file-1", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(storage.get("ab/file-1")).asString().isEqualTo("hello");
        storage.delete("ab/file-1");
        assertThatThrownBy(() -> storage.get("ab/file-1"))
                .isInstanceOf(StorageException.class);
    }

    @Test
    void rejectsPathTraversal() {
        var storage = new LocalFileObjectStorage(directory);

        assertThatThrownBy(() -> storage.put("../escape", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }
}
