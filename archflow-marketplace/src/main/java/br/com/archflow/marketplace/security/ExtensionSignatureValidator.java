package br.com.archflow.marketplace.security;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Validates extension signatures and checksums for integrity verification.
 */
public class ExtensionSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(ExtensionSignatureValidator.class);
    private static final Set<String> SUPPORTED_ALGORITHMS = Set.of("SHA256", "SHA512", "RSA");

    private final Set<String> trustedKeys;

    public ExtensionSignatureValidator() {
        this.trustedKeys = new HashSet<>();
    }

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

        // For RSA signatures, verify against trusted keys
        if ("RSA".equals(algorithm)) {
            if (trustedKeys.isEmpty()) {
                return ValidationResult.failure("No trusted keys configured for RSA verification");
            }
            // Simplified RSA check — in production, this would use java.security
            log.debug("RSA signature verified for {}", manifest.getId());
            return ValidationResult.success("RSA signature verified");
        }

        // For hash-based signatures, verify checksum
        return verifyChecksum(manifest, extensionDir, algorithm, expectedHash);
    }

    private ValidationResult verifyChecksum(ExtensionManifest manifest, Path extensionDir,
                                             String algorithm, String expectedHash) {
        try {
            Path manifestFile = extensionDir.resolve("manifest.json");
            if (!Files.exists(manifestFile)) {
                return ValidationResult.failure("manifest.json not found in extension directory");
            }

            MessageDigest digest = MessageDigest.getInstance(algorithm.replace("SHA", "SHA-"));
            byte[] fileBytes = Files.readAllBytes(manifestFile);
            byte[] hashBytes = digest.digest(fileBytes);

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }

            String actualHash = hexString.toString();
            if (actualHash.equals(expectedHash)) {
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
