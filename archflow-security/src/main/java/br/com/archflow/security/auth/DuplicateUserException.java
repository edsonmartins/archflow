package br.com.archflow.security.auth;

/**
 * Já existe um usuário com o nome de login que se tentou gravar.
 *
 * <p>Quem cria usuário confere antes ({@link UserRepository#existsByUsername}), mas entre a conferência e
 * a gravação outra requisição pode ter gravado o mesmo nome (dois administradores convidando o mesmo
 * e-mail ao mesmo tempo). O repositório traduz a violação da unicidade neste tipo para que quem chama
 * responda com o conflito legível, e não com um erro genérico de gravação.
 */
public class DuplicateUserException extends RuntimeException {

    public DuplicateUserException(String message, Throwable cause) {
        super(message, cause);
    }

    public DuplicateUserException(String message) {
        super(message);
    }
}
