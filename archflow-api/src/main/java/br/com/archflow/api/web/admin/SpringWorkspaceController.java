package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.WorkspaceController;
import br.com.archflow.api.admin.dto.ApiKeyDto;
import br.com.archflow.api.admin.dto.ApiKeyDto.*;
import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/workspace")
public class SpringWorkspaceController {

    private final WorkspaceController delegate;

    public SpringWorkspaceController(WorkspaceController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/users")
    public List<UserDto> listUsers() { return delegate.listUsers(); }

    @PostMapping("/users/invite")
    public UserDto inviteUser(@RequestBody InviteUserRequest request) { return delegate.inviteUser(request); }

    @PutMapping("/users/{id}")
    public UserDto updateUserRole(@PathVariable String id, @RequestBody UpdateRoleRequest request) { return delegate.updateUserRole(id, request); }

    @DeleteMapping("/users/{id}")
    public void removeUser(@PathVariable String id) { delegate.removeUser(id); }

    @PostMapping("/users/{id}/revoke")
    public void revokeInvite(@PathVariable String id) { delegate.revokeInvite(id); }

    @GetMapping("/keys")
    public List<ApiKeyDto> listApiKeys() { return delegate.listApiKeys(); }

    @PostMapping("/keys")
    public CreateApiKeyResponse createApiKey(@RequestBody CreateApiKeyRequest request) { return delegate.createApiKey(request); }

    @DeleteMapping("/keys/{id}")
    public void revokeApiKey(@PathVariable String id) { delegate.revokeApiKey(id); }
}
