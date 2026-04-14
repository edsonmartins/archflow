package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InMemoryUserRepository.
 *
 * <p>Covers CRUD operations, existence checks, and the default admin user that
 * is created by the no-arg constructor.</p>
 */
class InMemoryUserRepositoryTest {

    private InMemoryUserRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUserRepository();
    }

    // ========== Constructor / default admin ==========

    @Test
    void noArgConstructor_createsDefaultAdminUser() {
        Optional<User> admin = repository.findByUsername("admin");

        assertTrue(admin.isPresent());
        assertEquals("admin", admin.get().getUsername());
    }

    @Test
    void noArgConstructor_defaultAdminHasAdminRole() {
        User admin = repository.findByUsername("admin").orElseThrow();

        assertTrue(admin.hasRole("ADMIN"));
    }

    @Test
    void noArgConstructor_repositoryCountIsOne() {
        assertEquals(1, repository.count());
    }

    // ========== findByUsername ==========

    @Test
    void findByUsername_returnsUserForExistingUsername() {
        User user = createAndSaveUser("alice", "alice@example.com");

        Optional<User> found = repository.findByUsername("alice");

        assertTrue(found.isPresent());
        assertEquals("alice", found.get().getUsername());
    }

    @Test
    void findByUsername_returnsEmptyForUnknownUsername() {
        Optional<User> found = repository.findByUsername("nobody");

        assertFalse(found.isPresent());
    }

    // ========== findById ==========

    @Test
    void findById_returnsUserForExistingId() {
        User user = createAndSaveUser("bob", "bob@example.com");
        String id = user.getId();

        Optional<User> found = repository.findById(id);

        assertTrue(found.isPresent());
        assertEquals(id, found.get().getId());
    }

    @Test
    void findById_returnsEmptyForUnknownId() {
        Optional<User> found = repository.findById("non-existent-id");

        assertFalse(found.isPresent());
    }

    // ========== save (new user) ==========

    @Test
    void save_newUser_assignsId() {
        User user = buildUser(null, "carol", "carol@example.com");

        User saved = repository.save(user);

        assertNotNull(saved.getId());
        assertFalse(saved.getId().isBlank());
    }

    @Test
    void save_newUser_assignsCreatedAt() {
        User user = buildUser(null, "dave", "dave@example.com");

        User saved = repository.save(user);

        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void save_newUser_setsUpdatedAt() {
        User user = buildUser(null, "eve", "eve@example.com");

        User saved = repository.save(user);

        assertNotNull(saved.getUpdatedAt());
    }

    @Test
    void save_newUser_isRetrievableByUsernameAndId() {
        User user = buildUser(null, "frank", "frank@example.com");
        User saved = repository.save(user);

        assertTrue(repository.findByUsername("frank").isPresent());
        assertTrue(repository.findById(saved.getId()).isPresent());
    }

    // ========== save (update) ==========

    @Test
    void save_existingUser_updatesFields() {
        User user = createAndSaveUser("grace", "grace@example.com");
        user.setEmail("grace-updated@example.com");

        repository.save(user);

        User updated = repository.findByUsername("grace").orElseThrow();
        assertEquals("grace-updated@example.com", updated.getEmail());
    }

    @Test
    void save_existingUser_updatesUpdatedAt() throws InterruptedException {
        User user = createAndSaveUser("hank", "hank@example.com");
        var firstUpdatedAt = user.getUpdatedAt();

        Thread.sleep(5); // ensure at least 1 ms passes for LocalDateTime granularity
        repository.save(user);

        var secondUpdatedAt = repository.findByUsername("hank").orElseThrow().getUpdatedAt();
        // updatedAt should be the same or later (clock resolution may vary)
        assertFalse(secondUpdatedAt.isBefore(firstUpdatedAt));
    }

    @Test
    void save_existingUser_doesNotIncrementCount() {
        User user = createAndSaveUser("ivan", "ivan@example.com");
        int countBefore = repository.count();

        repository.save(user); // re-save same user

        assertEquals(countBefore, repository.count());
    }

    // ========== delete ==========

    @Test
    void delete_removesUserFromAllMaps() {
        User user = createAndSaveUser("julia", "julia@example.com");

        repository.delete(user);

        assertFalse(repository.findByUsername("julia").isPresent());
        assertFalse(repository.findById(user.getId()).isPresent());
        assertFalse(repository.existsByEmail("julia@example.com"));
    }

    @Test
    void delete_decrementsCount() {
        User user = createAndSaveUser("karl", "karl@example.com");
        int countBefore = repository.count();

        repository.delete(user);

        assertEquals(countBefore - 1, repository.count());
    }

    // ========== existsByUsername / existsByEmail ==========

    @Test
    void existsByUsername_returnsTrueForExistingUser() {
        createAndSaveUser("leo", "leo@example.com");

        assertTrue(repository.existsByUsername("leo"));
    }

    @Test
    void existsByUsername_returnsFalseForNonExistingUser() {
        assertFalse(repository.existsByUsername("nobody"));
    }

    @Test
    void existsByEmail_returnsTrueForExistingEmail() {
        createAndSaveUser("mia", "mia@example.com");

        assertTrue(repository.existsByEmail("mia@example.com"));
    }

    @Test
    void existsByEmail_returnsFalseForNonExistingEmail() {
        assertFalse(repository.existsByEmail("unknown@example.com"));
    }

    // ========== count ==========

    @Test
    void count_reflectsNumberOfSavedUsers() {
        int initial = repository.count(); // 1 (default admin)

        createAndSaveUser("nina", "nina@example.com");
        createAndSaveUser("omar", "omar@example.com");

        assertEquals(initial + 2, repository.count());
    }

    // ========== clear ==========

    @Test
    void clear_removesAllUsers() {
        createAndSaveUser("pete", "pete@example.com");

        repository.clear();

        assertEquals(0, repository.count());
        assertFalse(repository.findByUsername("admin").isPresent());
        assertFalse(repository.findByUsername("pete").isPresent());
    }

    @Test
    void clear_makesExistsByUsernameReturnFalse() {
        repository.clear();

        assertFalse(repository.existsByUsername("admin"));
    }

    // ========== Helpers ==========

    /** Builds a User without an id (triggers auto-assign on save). */
    private User buildUser(String id, String username, String email) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("$2a$04$somehashvalue");
        user.setEnabled(true);
        return user;
    }

    /** Creates a user with no pre-assigned id and saves it. */
    private User createAndSaveUser(String username, String email) {
        User user = buildUser(null, username, email);
        return repository.save(user);
    }
}
