package br.com.archflow.security.auth;

import br.com.archflow.model.security.User;

import java.util.Optional;

/**
 * Repository interface for User operations.
 *
 * Implementations should connect to the actual data store (database, in-memory, etc.).
 */
public interface UserRepository {

    /**
     * Finds a user by username.
     *
     * @param username The username
     * @return The user if found
     */
    Optional<User> findByUsername(String username);

    /**
     * Finds a user by ID.
     *
     * @param id The user ID
     * @return The user if found
     */
    Optional<User> findById(String id);

    /**
     * Saves a user (create or update).
     *
     * @param user The user to save
     * @return The saved user
     */
    User save(User user);

    /**
     * Deletes a user.
     *
     * @param user The user to delete
     */
    void delete(User user);

    /**
     * Checks if a user exists by username.
     *
     * @param username The username
     * @return true if the user exists
     */
    boolean existsByUsername(String username);

    /**
     * Checks if a user exists by email.
     *
     * @param email The email
     * @return true if the user exists
     */
    boolean existsByEmail(String email);
}
