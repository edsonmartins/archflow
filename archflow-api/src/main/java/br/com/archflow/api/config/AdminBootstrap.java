package br.com.archflow.api.config;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import br.com.archflow.security.password.PasswordService;
import org.slf4j.Logger;
import org.springframework.core.env.Environment;

/**
 * Regras de bootstrap do usuário administrador, compartilhadas pelo default em
 * memória ({@code ArchflowBeanConfiguration}) e pelo caminho durável JDBC
 * ({@code JdbcPersistenceConfiguration}), para que a resolução da senha e os
 * atributos do admin não divirjam entre os dois modos.
 */
final class AdminBootstrap {

    private AdminBootstrap() {
    }

    /**
     * Resolve a senha do admin: usa a configurada; na ausência, uma fixa de
     * desenvolvimento sob os profiles {@code dev}/{@code test}, ou uma aleatória
     * (logada uma única vez ao WARN) caso contrário — nunca uma credencial
     * publicamente conhecida em produção.
     */
    static String resolvePassword(Environment environment, String configuredPassword, Logger log) {
        if (configuredPassword != null && !configuredPassword.isBlank()) {
            return configuredPassword;
        }
        if (Profiles.isDevLike(environment)) {
            log.warn("Using fixed development admin password (dev/test profile). "
                    + "Set archflow.security.admin-password for real deployments.");
            return "admin123";
        }
        String generated = PasswordService.generateRandomPassword(24);
        log.warn("No admin password configured — generated a random one for user 'admin': {} "
                + "(set archflow.security.admin-password or ARCHFLOW_ADMIN_PASSWORD to control it)",
                generated);
        return generated;
    }

    /**
     * Monta o usuário {@code admin} de bootstrap com o hash já calculado.
     */
    static User buildAdmin(String passwordHash) {
        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@archflow.local");
        admin.setPasswordHash(passwordHash);
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setEnabled(true);
        admin.addRole(Role.createAdminRole());
        return admin;
    }
}
