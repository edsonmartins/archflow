package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory implementation of UserRepository.
 *
 * Useful for testing and development. In production, use a database-backed implementation.
 */
public class InMemoryUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserRepository.class);

    private final Map<String, User> usersById = new HashMap<>();
    private final Map<String, User> usersByUsername = new HashMap<>();
    private final Map<String, User> usersByEmail = new HashMap<>();

    public InMemoryUserRepository() {
        // Create default admin user
        createDefaultAdminUser();
    }

    /**
     * Creates a default admin user.
     * Username: admin
     * Password: admin123 (CHANGE THIS IN PRODUCTION!)
     */
    private void createDefaultAdminUser() {
        User admin = new User();
        admin.setId("user-admin-" + UUID.randomUUID());
        admin.setUsername("admin");
        admin.setEmail("admin@archflow.local");
        admin.setPasswordHash("$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYzW5qG.lXa"); // "admin123" hashed
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setEnabled(true);

        // Add admin role
        Role adminRole = Role.createAdminRole();
        admin.addRole(adminRole);

        save(admin);
        log.info("Default admin user created: admin / admin123");
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(usersById.get(id));
    }

    @Override
    public User save(User user) {
        if (user.getId() == null) {
            user.setId("user-" + UUID.randomUUID());
            user.setCreatedAt(LocalDateTime.now());
        }
        user.setUpdatedAt(LocalDateTime.now());

        // Remove old mappings if username/email changed
        User oldUser = usersById.get(user.getId());
        if (oldUser != null) {
            if (!oldUser.getUsername().equals(user.getUsername())) {
                usersByUsername.remove(oldUser.getUsername());
            }
            if (oldUser.getEmail() != null && !oldUser.getEmail().equals(user.getEmail())) {
                usersByEmail.remove(oldUser.getEmail());
            }
        }

        usersById.put(user.getId(), user);
        usersByUsername.put(user.getUsername(), user);
        if (user.getEmail() != null) {
            usersByEmail.put(user.getEmail(), user);
        }

        return user;
    }

    @Override
    public void delete(User user) {
        usersById.remove(user.getId());
        usersByUsername.remove(user.getUsername());
        if (user.getEmail() != null) {
            usersByEmail.remove(user.getEmail());
        }
    }

    @Override
    public boolean existsByUsername(String username) {
        return usersByUsername.containsKey(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return usersByEmail.containsKey(email);
    }

    /**
     * Gets the number of users in the repository.
     */
    public int count() {
        return usersById.size();
    }

    /**
     * Clears all users (useful for testing).
     */
    public void clear() {
        usersById.clear();
        usersByUsername.clear();
        usersByEmail.clear();
    }
}
