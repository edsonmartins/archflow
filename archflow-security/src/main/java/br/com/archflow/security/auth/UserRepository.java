package br.com.archflow.security.auth;

import br.com.archflow.model.security.User;

import java.util.List;
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
     * @throws DuplicateUserException quando outro usuário já tem esse nome de login — a mesma regra do
     *                                {@code UNIQUE(username)} da tabela, para quem checou antes e
     *                                perdeu a corrida para outra gravação
     */
    User save(User user);

    /**
     * Consome um convite pendente numa única escrita: grava a senha, ativa o login e apaga o hash e a
     * validade do convite — <b>só se</b> o hash guardado ainda for {@code invitationTokenHash}.
     *
     * <p>Existe porque ler o usuário, conferir o hash e depois {@link #save} deixava dois aceites
     * simultâneos do mesmo link passarem pela conferência antes de qualquer um gravar: os dois
     * respondiam 200 e valia a última senha. Com a comparação e a limpeza na mesma escrita, só um vence.
     *
     * @param userId              o usuário convidado
     * @param invitationTokenHash o hash do segredo que o aceite apresentou (já conferido)
     * @param passwordHash        o hash da senha escolhida
     * @return {@code true} se este aceite consumiu o convite; {@code false} se ele já não estava
     *         pendente com esse hash (outro aceite venceu, ou foi revogado)
     */
    boolean consumeInvitation(String userId, String invitationTokenHash, String passwordHash);

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

    /**
     * Todos os usuários. Existe para a tela de usuários do workspace, que filtra pelo tenant de cada
     * um ({@link User#getTenantId()}): é a lista de quem de fato entra pelo login (RCHFLW-85).
     *
     * @return os usuários, em qualquer ordem
     */
    List<User> findAll();
}
