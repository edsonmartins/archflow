package br.com.archflow.api.admin.dto;

public record UserDto(
        String id,
        String name,
        String email,
        String role,
        String status,
        String lastAccessAt,
        int workflowCount
) {
    public record InviteUserRequest(
            String email,
            String role
    ) {}

    public record UpdateRoleRequest(
            String role
    ) {}
}
