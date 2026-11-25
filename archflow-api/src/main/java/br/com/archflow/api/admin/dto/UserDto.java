package br.com.archflow.api.admin.dto;

import java.util.List;

/**
 * Um usuário do workspace, como o login o conhece (RCHFLW-85).
 *
 * @param id                  o id do usuário no repositório do login
 * @param name                o nome completo, ou o nome de login quando não há nome
 * @param email               o e-mail (também o nome de login de um convidado)
 * @param role                o papel do backend ({@code ADMIN}, {@code DESIGNER}, {@code EXECUTOR},
 *                            {@code VIEWER}); {@code null} sem papel
 * @param status              {@code active}, {@code invited} (convite pendente) ou {@code disabled}
 * @param lastAccessAt        o último login (ISO-8601), ou {@code null} se nunca entrou
 * @param invitationExpiresAt até quando o convite pendente vale (ISO-8601); {@code null} sem convite
 */
public record UserDto(
        String id,
        String name,
        String email,
        String role,
        String status,
        String lastAccessAt,
        String invitationExpiresAt
) {
    public record InviteUserRequest(
            String email,
            String role
    ) {}

    public record UpdateRoleRequest(
            String role
    ) {}

    /**
     * O convite criado. {@code invitationToken} vai no link de aceite e só existe nesta resposta: o
     * produto não envia e-mail, o administrador repassa o link.
     */
    public record InviteUserResponse(
            UserDto user,
            String invitationToken,
            String expiresAt
    ) {}

    /** Um papel do backend e as permissões ({@code recurso:acao}) que ele concede. */
    public record RoleDto(
            String name,
            String description,
            List<String> permissions
    ) {}

    /** O aceite de um convite: o token do link e a senha escolhida. */
    public record AcceptInvitationRequest(
            String token,
            String password
    ) {}

    /** O convite aceito: o nome com que o usuário entra no login. */
    public record AcceptInvitationResponse(
            String username
    ) {}
}
