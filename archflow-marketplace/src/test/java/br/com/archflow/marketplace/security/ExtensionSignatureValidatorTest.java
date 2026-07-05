package br.com.archflow.marketplace.security;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
    void shouldAcceptValidRsaSignatureFromTrustedKey(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = generateKeyPair();
        writeManifestJson(tempDir);
        String signature = ExtensionSignatureValidator.signRsa(tempDir, keyPair.getPrivate());

        validator = new ExtensionSignatureValidator(
                Set.of(ExtensionSignatureValidator.encodePublicKey(keyPair.getPublic())));

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(buildManifest(signature), tempDir);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("RSA signature verified");
    }

    @Test
    void shouldRejectForgedRsaSignature(@TempDir Path tempDir) throws Exception {
        KeyPair trustedKeyPair = generateKeyPair();
        writeManifestJson(tempDir);

        validator = new ExtensionSignatureValidator(
                Set.of(ExtensionSignatureValidator.encodePublicKey(trustedKeyPair.getPublic())));

        // Random bytes posing as a signature must never verify
        byte[] forged = new byte[256];
        new java.security.SecureRandom().nextBytes(forged);
        String forgedSignature = "RSA:" + java.util.Base64.getEncoder().encodeToString(forged);

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(buildManifest(forgedSignature), tempDir);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("verification failed");
    }

    @Test
    void shouldRejectRsaSignatureFromUntrustedKey(@TempDir Path tempDir) throws Exception {
        KeyPair trustedKeyPair = generateKeyPair();
        KeyPair untrustedKeyPair = generateKeyPair();
        writeManifestJson(tempDir);
        String signature = ExtensionSignatureValidator.signRsa(tempDir, untrustedKeyPair.getPrivate());

        validator = new ExtensionSignatureValidator(
                Set.of(ExtensionSignatureValidator.encodePublicKey(trustedKeyPair.getPublic())));

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(buildManifest(signature), tempDir);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("verification failed");
    }

    @Test
    void shouldRejectRsaSignatureWhenManifestTampered(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = generateKeyPair();
        writeManifestJson(tempDir);
        String signature = ExtensionSignatureValidator.signRsa(tempDir, keyPair.getPrivate());

        // Tamper with manifest.json after signing
        Files.writeString(tempDir.resolve("manifest.json"),
                "{\"name\":\"test\",\"version\":\"1.0.0\",\"entryPoint\":\"Evil\"}");

        validator = new ExtensionSignatureValidator(
                Set.of(ExtensionSignatureValidator.encodePublicKey(keyPair.getPublic())));

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(buildManifest(signature), tempDir);

        assertThat(result.valid()).isFalse();
    }

    @Test
    void shouldIgnoreEmbeddedSignatureFieldWhenVerifying(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = generateKeyPair();
        writeManifestJson(tempDir);
        String signature = ExtensionSignatureValidator.signRsa(tempDir, keyPair.getPrivate());

        // Re-write the manifest WITH the signature embedded — canonicalization
        // must strip it so verification still passes
        Files.writeString(tempDir.resolve("manifest.json"),
                "{\"name\":\"test\",\"version\":\"1.0.0\",\"signature\":\"" + signature + "\"}");

        validator = new ExtensionSignatureValidator(
                Set.of(ExtensionSignatureValidator.encodePublicKey(keyPair.getPublic())));

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(buildManifest(signature), tempDir);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void shouldRejectRsaSignatureThatIsNotBase64(@TempDir Path tempDir) throws Exception {
        writeManifestJson(tempDir);
        validator = new ExtensionSignatureValidator(Set.of("any-key"));

        ExtensionSignatureValidator.ValidationResult result =
                validator.validate(buildManifest("RSA:not!!valid@@base64"), tempDir);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Base64");
    }

    @Test
    void shouldAddAndRemoveTrustedKeys(@TempDir Path tempDir) throws Exception {
        KeyPair keyPair = generateKeyPair();
        writeManifestJson(tempDir);
        String signature = ExtensionSignatureValidator.signRsa(tempDir, keyPair.getPrivate());
        String publicKey = ExtensionSignatureValidator.encodePublicKey(keyPair.getPublic());
        ExtensionManifest manifest = buildManifest(signature);

        validator.addTrustedKey(publicKey);
        assertThat(validator.validate(manifest, tempDir).valid()).isTrue();

        assertThat(validator.removeTrustedKey(publicKey)).isTrue();
        assertThat(validator.validate(manifest, tempDir).valid()).isFalse();
    }

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static void writeManifestJson(Path dir) throws IOException {
        Files.writeString(dir.resolve("manifest.json"),
                "{\"name\":\"test\",\"version\":\"1.0.0\"}");
    }

    private static ExtensionManifest buildManifest(String signature) {
        return ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .signature(signature)
                .build();
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
