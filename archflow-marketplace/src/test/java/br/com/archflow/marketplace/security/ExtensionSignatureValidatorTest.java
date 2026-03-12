package br.com.archflow.marketplace.security;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExtensionSignatureValidator}.
 */
class ExtensionSignatureValidatorTest {

    private ExtensionSignatureValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ExtensionSignatureValidator();
    }

    @Test
    void shouldRejectNullSignature() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature(null)
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("no signature");
    }

    @Test
    void shouldRejectEmptySignature() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("no signature");
    }

    @Test
    void shouldRejectInvalidFormat() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("nocolonhere")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Invalid signature format");
    }

    @Test
    void shouldRejectUnsupportedAlgorithm() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("MD5:abc123")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Unsupported signature algorithm");
    }

    @Test
    void shouldRejectEmptyHash() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("SHA256: ")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Empty signature hash");
    }

    @Test
    void shouldRejectRsaWithoutTrustedKeys() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("RSA:somesignature")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("No trusted keys configured");
    }

    @Test
    void shouldAcceptRsaWithTrustedKeys() {
        validator = new ExtensionSignatureValidator(Set.of("trusted-key-1"));

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("RSA:somesignature")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("RSA signature verified");
    }

    @Test
    void shouldAddAndRemoveTrustedKeys() {
        validator.addTrustedKey("key-1");
        validator.addTrustedKey("key-2");

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("RSA:somesignature")
                .build();

        // Should accept with trusted keys
        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, Path.of("/tmp"));
        assertThat(result.valid()).isTrue();

        // Remove all keys
        boolean removed1 = validator.removeTrustedKey("key-1");
        boolean removed2 = validator.removeTrustedKey("key-2");
        assertThat(removed1).isTrue();
        assertThat(removed2).isTrue();

        // Should reject without trusted keys
        result = validator.validate(manifest, Path.of("/tmp"));
        assertThat(result.valid()).isFalse();
    }

    @Test
    void shouldVerifyChecksumSuccess(@TempDir Path tempDir) throws IOException, NoSuchAlgorithmException {
        // Create a manifest.json file and compute its SHA-256 hash
        Path manifestFile = tempDir.resolve("manifest.json");
        String content = "{\"name\":\"test\",\"version\":\"1.0.0\"}";
        Files.writeString(manifestFile, content);

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = digest.digest(content.getBytes());
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("SHA256:" + hexString)
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, tempDir);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("Checksum verified");
    }

    @Test
    void shouldRejectChecksumMismatch(@TempDir Path tempDir) throws IOException {
        Path manifestFile = tempDir.resolve("manifest.json");
        Files.writeString(manifestFile, "{\"name\":\"test\"}");

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature("SHA256:0000000000000000000000000000000000000000000000000000000000000000")
                .build();

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(manifest, tempDir);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Checksum mismatch");
    }
}
