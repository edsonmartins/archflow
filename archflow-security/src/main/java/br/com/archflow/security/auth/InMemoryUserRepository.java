package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of UserRepository.
 *
 * Useful for testing and development. In production, use a database-backed implementation.
 */
public class InMemoryUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserRepository.class);

    // Concorrentes: a tela de usuários do workspace convida, aceita e remove enquanto o login lê.
    private final Map<String, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, User> usersByUsername = new ConcurrentHashMap<>();
    private final Map<String, User> usersByEmail = new ConcurrentHashMap<>();

    /**
     * Creates an empty repository — no default users. Callers that need a
     * bootstrap admin must seed it explicitly with a password hash they
     * control (see {@link #InMemoryUserRepository(String)}); the repository
     * never embeds credentials of its own.
     */
    public InMemoryUserRepository() {
    }

    /**
     * Creates a repository seeded with a default {@code admin} user whose
     * password hash is supplied by the caller.
     *
     * @param adminPasswordHash BCrypt hash for the admin user's password
     */
    public InMemoryUserRepository(String adminPasswordHash) {
        createDefaultAdminUser(adminPasswordHash);
    }

    private void createDefaultAdminUser(String passwordHash) {
        User admin = new User();
        admin.setId("user-admin-" + UUID.randomUUID());
        admin.setUsername("admin");
        admin.setEmail("admin@archflow.local");
        admin.setPasswordHash(passwordHash);
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setEnabled(true);

        // Add admin role
        Role adminRole = Role.createAdminRole();
        admin.addRole(adminRole);

        save(admin);
        log.info("Default admin user created (username: admin)");
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username));
    }

    @Override
    public Optional<User> findById(String id) {
        return Optional.ofNullable(usersById.get(id));
    }

    /**
     * Sincronizado, e recusando um nome de login de outro usuário ({@link DuplicateUserException}): é a
     * regra do {@code UNIQUE(username)} da tabela. Sem ela, dois convites simultâneos do mesmo e-mail
     * deixavam dois usuários, e o segundo tomava o nome do primeiro no índice.
     */
    @Override
    public synchronized User save(User user) {
        User holder = user.getUsername() == null ? null : usersByUsername.get(user.getUsername());
        if (holder != null && !holder.getId().equals(user.getId())) {
            throw new DuplicateUserException("A user with the username " + user.getUsername() + " already exists");
        }
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

    /** Sob o mesmo monitor de {@link #save}: conferir e limpar o hash é uma operação só. */
    @Override
    public synchronized boolean consumeInvitation(String userId, String invitationTokenHash, String passwordHash) {
        User user = userId == null ? null : usersById.get(userId);
        if (user == null || invitationTokenHash == null
                || !invitationTokenHash.equals(user.getInvitationTokenHash())) {
            return false;
        }
        user.setPasswordHash(passwordHash);
        user.setEnabled(true);
        user.setInvitationTokenHash(null);
        user.setInvitationExpiresAt(null);
        user.setUpdatedAt(LocalDateTime.now());
        return true;
    }

    @Override
    public synchronized void delete(User user) {
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

    @Override
    public List<User> findAll() {
        return List.copyOf(usersById.values());
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
