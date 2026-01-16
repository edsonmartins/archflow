package br.com.archflow.security.password;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for password hashing and verification using BCrypt.
 *
 * BCrypt is a secure hashing algorithm designed specifically for passwords.
 * It includes a salt and is computationally intensive to prevent brute force attacks.
 */
public class PasswordService {

    private static final Logger log = LoggerFactory.getLogger(PasswordService.class);

    /**
     * Default BCrypt cost factor (12).
     * Higher values are more secure but slower.
     * 12 is a good balance for most applications (approx 300ms per hash).
     */
    private static final int DEFAULT_COST = 12;

    private final int cost;

    /**
     * Creates a PasswordService with default cost factor.
     */
    public PasswordService() {
        this(DEFAULT_COST);
    }

    /**
     * Creates a PasswordService with a custom cost factor.
     *
     * @param cost The BCrypt cost factor (4-31, higher is slower)
     */
    public PasswordService(int cost) {
        if (cost < 4 || cost > 31) {
            throw new IllegalArgumentException("Cost factor must be between 4 and 31");
        }
        this.cost = cost;
    }

    /**
     * Hashes a plain text password.
     *
     * @param plainPassword The plain text password
     * @return The BCrypt hash (includes salt)
     */
    public String hash(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }
        // Use the bcrypt library with the specified cost
        return BCrypt.withDefaults().hashToString(cost, plainPassword.toCharArray());
    }

    /**
     * Verifies a plain text password against a hash.
     *
     * @param plainPassword The plain text password to verify
     * @param hash The BCrypt hash to verify against
     * @return true if the password matches, false otherwise
     */
    public boolean verify(String plainPassword, String hash) {
        if (plainPassword == null || hash == null) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(plainPassword.toCharArray(), hash).verified;
        } catch (Exception e) {
            log.warn("Password verification failed", e);
            return false;
        }
    }

    /**
     * Checks if a hash needs to be rehashed (e.g., when cost factor changes).
     *
     * BCrypt hashes include the cost factor in the hash itself.
     * We extract it and compare with our current cost.
     *
     * @param hash The BCrypt hash to check
     * @return true if the hash uses a different cost factor
     */
    public boolean needsRehash(String hash) {
        try {
            // Extract the cost from the hash (it's embedded after the "$2a$" or similar prefix)
            // BCrypt hash format: $2a$cost$saltandhash
            String[] parts = hash.split("\\$");
            if (parts.length >= 3) {
                try {
                    int hashCost = Integer.parseInt(parts[2]);
                    return hashCost != cost;
                } catch (NumberFormatException e) {
                    log.warn("Failed to parse cost from hash", e);
                    return true;
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to extract cost from hash", e);
            return true;
        }
    }

    /**
     * Generates a secure random password.
     *
     * @param length The length of the password
     * @return A random password with letters, numbers, and special characters
     */
    public static String generateRandomPassword(int length) {
        String upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        String lower = "abcdefghijklmnopqrstuvwxyz";
        String digits = "0123456789";
        String special = "!@#$%^&*()-_=+<>?";

        String all = upper + lower + digits + special;
        StringBuilder password = new StringBuilder();

        for (int i = 0; i < length; i++) {
            int index = (int) (Math.random() * all.length());
            password.append(all.charAt(index));
        }

        return password.toString();
    }

    /**
     * Validates a password against security requirements.
     *
     * @param password The password to validate
     * @param minLength Minimum length requirement
     * @param requireUppercase Whether uppercase letters are required
     * @param requireLowercase Whether lowercase letters are required
     * @param requireDigits Whether digits are required
     * @param requireSpecial Whether special characters are required
     * @return true if the password meets all requirements
     */
    public static boolean isValidPassword(String password, int minLength,
                                          boolean requireUppercase, boolean requireLowercase,
                                          boolean requireDigits, boolean requireSpecial) {
        if (password == null || password.length() < minLength) {
            return false;
        }

        if (requireUppercase && !password.matches(".*[A-Z].*")) {
            return false;
        }

        if (requireLowercase && !password.matches(".*[a-z].*")) {
            return false;
        }

        if (requireDigits && !password.matches(".*\\d.*")) {
            return false;
        }

        if (requireSpecial && !password.matches(".*[!@#$%^&*()\\-_=+\\[\\]{};:'\",.<>/?].*")) {
            return false;
        }

        return true;
    }

    /**
     * Gets the current cost factor.
     */
    public int getCost() {
        return cost;
    }
}
