package br.com.archflow.marketplace.security;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * Validates extension signatures and checksums for integrity verification.
 *
 * <p>Two signature schemes are supported, both computed over the <em>canonical
 * manifest content</em> — the {@code manifest.json} parsed, stripped of its
 * {@code signature} field, and re-serialized compactly with keys sorted. This
 * removes the self-reference problem of embedding a signature inside the very
 * file it covers, and makes verification independent of whitespace/key order.
 *
 * <ul>
 *   <li>{@code SHA256:<hex>} / {@code SHA512:<hex>} — integrity checksum.</li>
 *   <li>{@code RSA:<base64>} — SHA256withRSA signature, verified against the
 *       configured trusted keys (Base64-encoded X.509 public keys). The
 *       signature is valid if any trusted key verifies it.</li>
 * </ul>
 */
public class ExtensionSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(ExtensionSignatureValidator.class);
    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("SHA256", "SHA512", "RSA");
    private static final String RSA_SIGNATURE_ALGORITHM = "SHA256withRSA";

    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Set<String> trustedKeys;

    public ExtensionSignatureValidator() {
        this.trustedKeys = new HashSet<>();
    }

    /**
     * @param trustedKeys Base64-encoded X.509 (SubjectPublicKeyInfo) RSA public keys
     */
    public ExtensionSignatureValidator(Set<String> trustedKeys) {
        this.trustedKeys = new HashSet<>(trustedKeys);
    }

    public void addTrustedKey(String key) {
        trustedKeys.add(key);
    }

    public boolean removeTrustedKey(String key) {
        return trustedKeys.remove(key);
    }

    /**
     * Validates extension signature.
     *
     * @param manifest The extension manifest containing signature info
     * @param extensionDir The directory containing the extension files
     * @return ValidationResult with success/failure details
     */
    public ValidationResult validate(ExtensionManifest manifest, Path extensionDir) {
        String signature = manifest.getSignature();
        if (signature == null || signature.isEmpty()) {
            return ValidationResult.failure("Extension has no signature");
        }

        // Parse algorithm from signature (format: "ALGO:hash")
        int colonIdx = signature.indexOf(':');
        if (colonIdx <= 0) {
            return ValidationResult.failure("Invalid signature format: expected 'ALGORITHM:hash'");
        }

        String algorithm = signature.substring(0, colonIdx);
        String expectedHash = signature.substring(colonIdx + 1);

        if (!SUPPORTED_ALGORITHMS.contains(algorithm)) {
            return ValidationResult.failure("Unsupported signature algorithm: " + algorithm);
        }

        if (expectedHash.isBlank()) {
            return ValidationResult.failure("Empty signature hash");
        }

        if ("RSA".equals(algorithm)) {
            if (trustedKeys.isEmpty()) {
                return ValidationResult.failure("No trusted keys configured for RSA verification");
            }
            return verifyRsaSignature(manifest, extensionDir, expectedHash);
        }

        return verifyChecksum(manifest, extensionDir, algorithm, expectedHash);
    }

    private ValidationResult verifyRsaSignature(ExtensionManifest manifest, Path extensionDir,
                                                String base64Signature) {
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(base64Signature);
        } catch (IllegalArgumentException e) {
            return ValidationResult.failure("RSA signature is not valid Base64");
        }

        byte[] content;
        try {
            content = canonicalManifestBytes(extensionDir);
        } catch (IOException e) {
            return ValidationResult.failure("Failed to read extension files: " + e.getMessage());
        }

        for (String encodedKey : trustedKeys) {
            PublicKey publicKey;
            try {
                publicKey = decodePublicKey(encodedKey);
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                // A malformed trusted key must never grant access; skip it but make noise.
                log.warn("Skipping malformed trusted key while verifying {}: {}",
                        manifest.getId(), e.getMessage());
                continue;
            }

            try {
                Signature verifier = Signature.getInstance(RSA_SIGNATURE_ALGORITHM);
                verifier.initVerify(publicKey);
                verifier.update(content);
                if (verifier.verify(signatureBytes)) {
                    log.info("RSA signature verified for {} ({})", manifest.getId(), RSA_SIGNATURE_ALGORITHM);
                    return ValidationResult.success("RSA signature verified");
                }
            } catch (GeneralSecurityException e) {
                log.debug("RSA verification attempt failed for {}: {}", manifest.getId(), e.getMessage());
            }
        }

        return ValidationResult.failure(
                "RSA signature verification failed: no trusted key matches the signature");
    }

    private ValidationResult verifyChecksum(ExtensionManifest manifest, Path extensionDir,
                                             String algorithm, String expectedHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.replace("SHA", "SHA-"));
            byte[] hashBytes = digest.digest(canonicalManifestBytes(extensionDir));

            String actualHash = toHex(hashBytes);
            // Hex é case-insensitive; normaliza antes de comparar para não
            // rejeitar um hash correto emitido em maiúsculas (ex.: certutil).
            String normalizedExpected = expectedHash.toLowerCase(java.util.Locale.ROOT);
            if (MessageDigest.isEqual(
                    actualHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    normalizedExpected.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return ValidationResult.success("Checksum verified (" + algorithm + ")");
            } else {
                return ValidationResult.failure(
                        "Checksum mismatch: expected " + expectedHash + ", got " + actualHash);
            }
        } catch (NoSuchAlgorithmException e) {
            return ValidationResult.failure("Algorithm not available: " + algorithm);
        } catch (IOException e) {
            return ValidationResult.failure("Failed to read extension files: " + e.getMessage());
        }
    }

    /**
     * Reads {@code manifest.json} from the extension directory and returns its
     * canonical bytes: parsed, {@code signature} field removed, re-serialized
     * compactly with map keys sorted.
     */
    public static byte[] canonicalManifestBytes(Path extensionDir) throws IOException {
        Path manifestFile = extensionDir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            throw new IOException("manifest.json not found in extension directory");
        }
        JsonNode tree = CANONICAL_MAPPER.readTree(Files.readAllBytes(manifestFile));
        if (tree instanceof ObjectNode objectNode) {
            objectNode.remove("signature");
        }
        Object asMap = CANONICAL_MAPPER.treeToValue(tree, Object.class);
        return CANONICAL_MAPPER.writeValueAsBytes(asMap);
    }

    /**
     * Signs the canonical manifest content of an extension directory. Intended
     * for publisher tooling and tests.
     *
     * @return signature in manifest format ({@code RSA:<base64>})
     */
    public static String signRsa(Path extensionDir, PrivateKey privateKey)
            throws IOException, GeneralSecurityException {
        Signature signer = Signature.getInstance(RSA_SIGNATURE_ALGORITHM);
        signer.initSign(privateKey);
        signer.update(canonicalManifestBytes(extensionDir));
        return "RSA:" + Base64.getEncoder().encodeToString(signer.sign());
    }

    /**
     * Encodes a public key in the trusted-key format (Base64 X.509/SPKI).
     */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    private static PublicKey decodePublicKey(String base64Key) throws GeneralSecurityException {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

    /**
     * Result of a signature validation.
     */
    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult success(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(false, message);
        }
    }
}
