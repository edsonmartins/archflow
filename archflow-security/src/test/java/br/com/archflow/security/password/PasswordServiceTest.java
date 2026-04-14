package br.com.archflow.security.password;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PasswordService.
 *
 * <p>Uses cost factor 4 (minimum) to keep tests fast while still
 * exercising BCrypt behaviour.</p>
 */
class PasswordServiceTest {

    private PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordService = new PasswordService(4); // low cost for speed
    }

    // ========== hash ==========

    @Test
    void hash_returnsNonNullNonEmptyString() {
        String hash = passwordService.hash("MyPassword1!");

        assertNotNull(hash);
        assertFalse(hash.isBlank());
    }

    @Test
    void hash_isDifferentFromPlaintext() {
        String plain = "MyPassword1!";
        String hash = passwordService.hash(plain);

        assertNotEquals(plain, hash);
    }

    @Test
    void hash_samePasswordProducesDifferentHashes() {
        // BCrypt uses a random salt, so two hashes of the same password must differ
        String hash1 = passwordService.hash("MyPassword1!");
        String hash2 = passwordService.hash("MyPassword1!");

        assertNotEquals(hash1, hash2);
    }

    @Test
    void hash_withNullThrowsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> passwordService.hash(null));
    }

    @Test
    void hash_withEmptyStringThrowsIllegalArgumentException() {
        // The implementation guards against empty strings
        assertThrows(IllegalArgumentException.class, () -> passwordService.hash(""));
    }

    // ========== verify ==========

    @Test
    void verify_returnsTrueForCorrectPassword() {
        String hash = passwordService.hash("CorrectHorseBatteryStaple1!");

        assertTrue(passwordService.verify("CorrectHorseBatteryStaple1!", hash));
    }

    @Test
    void verify_returnsFalseForWrongPassword() {
        String hash = passwordService.hash("CorrectPassword1!");

        assertFalse(passwordService.verify("WrongPassword1!", hash));
    }

    @Test
    void verify_returnsFalseForNullPassword() {
        String hash = passwordService.hash("SomePassword1!");

        assertFalse(passwordService.verify(null, hash));
    }

    @Test
    void verify_returnsFalseForNullHash() {
        assertFalse(passwordService.verify("SomePassword1!", null));
    }

    @Test
    void verify_returnsFalseForBothNull() {
        assertFalse(passwordService.verify(null, null));
    }

    // ========== needsRehash ==========

    @Test
    void needsRehash_returnsFalseWhenCostMatches() {
        // Hash produced with cost 4, service also uses cost 4
        String hash = passwordService.hash("Password1!");

        assertFalse(passwordService.needsRehash(hash));
    }

    @Test
    void needsRehash_returnsTrueWhenCostDiffers() {
        // Hash produced with cost 5; service uses cost 4
        PasswordService cost5Service = new PasswordService(5);
        String hash = cost5Service.hash("Password1!");

        assertTrue(passwordService.needsRehash(hash));
    }

    @Test
    void needsRehash_returnsTrueForInvalidHash() {
        // A non-BCrypt string has no embedded cost, so rehash is needed
        assertTrue(passwordService.needsRehash("not-a-bcrypt-hash"));
    }

    // ========== isValidPassword ==========

    @Test
    void isValidPassword_returnsTrueForCompliantPassword() {
        assertTrue(PasswordService.isValidPassword("SecurePass1!", 8, true, true, true, true));
    }

    @Test
    void isValidPassword_returnsFalseWhenTooShort() {
        assertFalse(PasswordService.isValidPassword("Ab1!", 8, true, true, true, true));
    }

    @Test
    void isValidPassword_returnsFalseWhenMissingUppercase() {
        assertFalse(PasswordService.isValidPassword("lowercase1!", 8, true, true, true, true));
    }

    @Test
    void isValidPassword_returnsFalseWhenMissingLowercase() {
        assertFalse(PasswordService.isValidPassword("UPPERCASE1!", 8, true, true, true, true));
    }

    @Test
    void isValidPassword_returnsFalseWhenMissingDigit() {
        assertFalse(PasswordService.isValidPassword("NoDigits!!", 8, true, true, true, true));
    }

    @Test
    void isValidPassword_returnsFalseWhenMissingSpecialChar() {
        assertFalse(PasswordService.isValidPassword("NoSpecial1A", 8, true, true, true, true));
    }

    @Test
    void isValidPassword_returnsFalseForNullPassword() {
        assertFalse(PasswordService.isValidPassword(null, 8, true, true, true, true));
    }

    @Test
    void isValidPassword_honorsRelaxedRequirements() {
        // Only min-length required, no character class checks
        assertTrue(PasswordService.isValidPassword("simplepswd", 8, false, false, false, false));
    }

    // ========== generateRandomPassword ==========

    @Test
    void generateRandomPassword_returnsCorrectLength() {
        String password = PasswordService.generateRandomPassword(16);

        assertEquals(16, password.length());
    }

    @Test
    void generateRandomPassword_returnsDifferentValuesEachCall() {
        String p1 = PasswordService.generateRandomPassword(16);
        String p2 = PasswordService.generateRandomPassword(16);

        // Statistically guaranteed to differ for length >= 8
        assertNotEquals(p1, p2);
    }

    @Test
    void generateRandomPassword_meetsValidationCriteria() {
        // The character pool contains upper, lower, digits, and specials — a 20-char
        // password almost certainly contains all classes; run a few times to be sure.
        for (int i = 0; i < 20; i++) {
            String password = PasswordService.generateRandomPassword(20);
            boolean valid = PasswordService.isValidPassword(password, 8, true, true, true, true);
            if (valid) {
                return; // at least one passed — good enough
            }
        }
        // If all 20 attempts failed validation the charset must be wrong
        fail("generateRandomPassword never produced a password that meets all criteria in 20 attempts");
    }

    // ========== constructor validation ==========

    @Test
    void constructor_rejectsCostBelowMinimum() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordService(3));
    }

    @Test
    void constructor_rejectsCostAboveMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordService(32));
    }

    @Test
    void constructor_acceptsMinimumCost() {
        assertDoesNotThrow(() -> new PasswordService(4));
    }

    @Test
    void constructor_acceptsMaximumCost() {
        // Cost 31 is allowed but would be extremely slow; we just check no exception is thrown
        assertDoesNotThrow(() -> new PasswordService(31));
    }

    @Test
    void getCost_returnsConfiguredCost() {
        PasswordService service = new PasswordService(6);

        assertEquals(6, service.getCost());
    }
}
