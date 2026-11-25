package br.com.archflow.api.web.auth;

import br.com.archflow.api.admin.dto.UserDto.AcceptInvitationRequest;
import br.com.archflow.api.admin.dto.UserDto.AcceptInvitationResponse;
import br.com.archflow.api.web.admin.SpringWorkspaceController;
import br.com.archflow.security.auth.WorkspaceUserService;
import br.com.archflow.security.auth.WorkspaceUserService.WorkspaceUserException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /api/auth/invitations/accept}: o convidado define a senha e o login dele passa a
 * entrar (RCHFLW-85).
 *
 * <p>Público como o login: quem aceita ainda não tem credencial. O que autentica o pedido é o token
 * do link — de uso único, com validade, guardado só como hash.
 */
@RestController
@RequestMapping("/api/auth/invitations")
public class SpringInvitationController {

    private final WorkspaceUserService users;

    public SpringInvitationController(WorkspaceUserService users) {
        this.users = users;
    }

    @PostMapping("/accept")
    public AcceptInvitationResponse accept(@RequestBody(required = false) AcceptInvitationRequest request) {
        if (request == null) {
            throw new WorkspaceUserException(WorkspaceUserException.Code.BODY_REQUIRED, "Request body is required");
        }
        return new AcceptInvitationResponse(users.acceptInvitation(request.token(), request.password()).getUsername());
    }

    @ExceptionHandler(WorkspaceUserException.class)
    public ResponseEntity<Map<String, Object>> handleRefusal(WorkspaceUserException ex) {
        return SpringWorkspaceController.refusal(ex);
    }
}
