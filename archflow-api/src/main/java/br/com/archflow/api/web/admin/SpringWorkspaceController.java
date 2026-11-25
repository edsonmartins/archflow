package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.WorkspaceController;
import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.WorkspaceSummaryDto;
import br.com.archflow.security.auth.WorkspaceUserService.WorkspaceUserException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * {@code /api/admin/workspace}: resumo e usuários do workspace (RCHFLW-85). Exige o papel
 * {@code ADMIN} ({@code AdminSecurityConfiguration}) — é aqui que se convida, troca papel e remove.
 *
 * <p>As chaves de API do workspace são as de {@code /api/apikeys}; o par {@code /keys} em memória
 * que existia aqui foi removido.
 */
@RestController
@RequestMapping("/api/admin/workspace")
public class SpringWorkspaceController {

    private final WorkspaceController delegate;

    public SpringWorkspaceController(WorkspaceController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/summary")
    public WorkspaceSummaryDto getSummary() { return delegate.getSummary(); }

    @GetMapping("/users")
    public List<UserDto> listUsers() { return delegate.listUsers(); }

    /** Os papéis do backend — a UI monta o seletor a partir daqui, sem uma lista própria. */
    @GetMapping("/roles")
    public List<RoleDto> listRoles() { return delegate.listRoles(); }

    /**
     * Corpo opcional no binding: sem ele o Spring respondia o 400 genérico ("Bad Request") antes de
     * chegar aqui; assim a falta do corpo vira a recusa tipada ({@code BODY_REQUIRED}), com frase.
     */
    @PostMapping("/users/invite")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteUserResponse inviteUser(@RequestBody(required = false) InviteUserRequest request) {
        return delegate.inviteUser(request);
    }

    @PutMapping("/users/{id}")
    public UserDto updateUserRole(@PathVariable String id, @RequestBody UpdateRoleRequest request) {
        return delegate.updateUserRole(id, request);
    }

    /** 204: sem corpo — um 200 vazio quebrava o {@code response.json()} do cliente da UI. */
    @DeleteMapping("/users/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeUser(@PathVariable String id) { delegate.removeUser(id); }

    @PostMapping("/users/{id}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@PathVariable String id) { delegate.revokeInvite(id); }

    /**
     * A recusa do serviço vira o status certo, com a frase dele — nunca um 500 cru. {@code code} é o
     * caso exato, para a UI mostrar a frase no idioma dela; {@code message} segue em inglês para
     * clientes de API.
     */
    @ExceptionHandler(WorkspaceUserException.class)
    public ResponseEntity<Map<String, Object>> handleRefusal(WorkspaceUserException ex) {
        return refusal(ex);
    }

    /** Compartilhado com o aceite do convite, para as duas pontas responderem igual. */
    public static ResponseEntity<Map<String, Object>> refusal(WorkspaceUserException ex) {
        HttpStatus status = switch (ex.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case EXPIRED -> HttpStatus.GONE;
            case INVALID -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .body(Map.of("error", ex.reason().name(), "code", ex.code().name(), "message", ex.getMessage(),
                        "status", status.value()));
    }
}
