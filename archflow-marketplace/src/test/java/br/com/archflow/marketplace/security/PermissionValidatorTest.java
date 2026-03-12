package br.com.archflow.marketplace.security;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PermissionValidator}.
 */
class PermissionValidatorTest {

    private PermissionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PermissionValidator();
    }

    @Test
    void shouldAcceptValidPermissions() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("ai:chat", "ai:stream"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("All permissions validated");
    }

    @Test
    void shouldRejectDangerousPermissions() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("SYSTEM_EXEC"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Dangerous permission denied");
        assertThat(result.message()).contains("SYSTEM_EXEC");
    }

    @Test
    void shouldRejectFileWriteRootDangerousPermission() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("FILE_WRITE_ROOT"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Dangerous permission denied");
    }

    @Test
    void shouldRejectNetworkUnrestrictedDangerousPermission() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("NETWORK_UNRESTRICTED"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Dangerous permission denied");
    }

    @Test
    void shouldAllowWhenDangerousPermissionsDisabled() {
        validator.setDenyDangerousPermissions(false);

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("SYSTEM_EXEC", "FILE_WRITE_ROOT"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void shouldValidateAllowedPermissions() {
        validator.addAllowedPermission("ai:chat");
        validator.addAllowedPermission("ai:stream");

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("ai:chat"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isTrue();
    }

    @Test
    void shouldRejectUnknownPermissions() {
        validator.addAllowedPermission("ai:chat");

        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .permissions(Set.of("custom:unknown"))
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("Permission not allowed");
    }

    @Test
    void shouldAcceptNoPermissions() {
        ExtensionManifest manifest = ExtensionManifest.builder()
                .name("test-ext")
                .version("1.0.0")
                .entryPoint("TestExt")
                .build();

        PermissionValidator.ValidationResult result = validator.validate(manifest);

        assertThat(result.valid()).isTrue();
        assertThat(result.message()).contains("No permissions requested");
    }
}
