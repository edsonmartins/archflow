package br.com.archflow.api.admin;

import br.com.archflow.api.admin.dto.UserDto;
import br.com.archflow.api.admin.dto.UserDto.*;
import br.com.archflow.api.admin.dto.ApiKeyDto;
import br.com.archflow.api.admin.dto.ApiKeyDto.*;
import br.com.archflow.api.admin.impl.WorkspaceControllerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WorkspaceControllerImpl")
class WorkspaceControllerImplTest {

    private WorkspaceControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new WorkspaceControllerImpl();
    }

    @Nested
    @DisplayName("Users")
    class Users {

        @Test
        @DisplayName("should list seeded users")
        void shouldListUsers() {
            var users = controller.listUsers();
            assertThat(users).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("should invite user")
        void shouldInviteUser() {
            var user = controller.inviteUser(new InviteUserRequest("new@co.com", "editor"));
            assertThat(user.email()).isEqualTo("new@co.com");
            assertThat(user.role()).isEqualTo("editor");
            assertThat(user.status()).isEqualTo("invited");
        }

        @Test
        @DisplayName("should update user role")
        void shouldUpdateRole() {
            var updated = controller.updateUserRole("u1", new UpdateRoleRequest("viewer"));
            assertThat(updated.role()).isEqualTo("viewer");
        }

        @Test
        @DisplayName("should remove user")
        void shouldRemoveUser() {
            int before = controller.listUsers().size();
            controller.removeUser("u1");
            assertThat(controller.listUsers()).hasSize(before - 1);
        }

        @Test
        @DisplayName("should revoke invite")
        void shouldRevokeInvite() {
            var invited = controller.inviteUser(new InviteUserRequest("temp@co.com", "viewer"));
            controller.revokeInvite(invited.id());
            assertThat(controller.listUsers().stream().noneMatch(u -> u.id().equals(invited.id()))).isTrue();
        }
    }

    @Nested
    @DisplayName("API Keys")
    class Keys {

        @Test
        @DisplayName("should list seeded keys")
        void shouldListKeys() {
            assertThat(controller.listApiKeys()).isNotEmpty();
        }

        @Test
        @DisplayName("should create production key with af_live_ prefix")
        void shouldCreateProductionKey() {
            var response = controller.createApiKey(new CreateApiKeyRequest("My Key", "production"));
            assertThat(response.prefix()).isEqualTo("af_live_");
            assertThat(response.fullKey()).startsWith("af_live_");
            assertThat(response.maskedKey()).contains("••••••••");
            assertThat(response.fullKey()).hasSize(24); // prefix(8) + hex(16)
        }

        @Test
        @DisplayName("should create staging key with af_test_ prefix")
        void shouldCreateStagingKey() {
            var response = controller.createApiKey(new CreateApiKeyRequest("Test", "staging"));
            assertThat(response.fullKey()).startsWith("af_test_");
        }

        @Test
        @DisplayName("should create web_component key with af_pub_ prefix")
        void shouldCreateWebComponentKey() {
            var response = controller.createApiKey(new CreateApiKeyRequest("Embed", "web_component"));
            assertThat(response.fullKey()).startsWith("af_pub_");
        }

        @Test
        @DisplayName("should revoke key")
        void shouldRevokeKey() {
            var created = controller.createApiKey(new CreateApiKeyRequest("Temp", "staging"));
            controller.revokeApiKey(created.id());
            assertThat(controller.listApiKeys().stream().noneMatch(k -> k.id().equals(created.id()))).isTrue();
        }
    }
}
