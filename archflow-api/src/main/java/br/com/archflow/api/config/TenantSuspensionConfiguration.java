package br.com.archflow.api.config;

import br.com.archflow.api.admin.impl.TenantControllerImpl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra o {@link TenantSuspensionInterceptor} em toda a API (QA2 P6). Separado do
 * {@link AdminSecurityConfiguration}, que trata de papel e permissão: a suspensão vale para qualquer
 * papel do tenant, em qualquer endpoint.
 */
@Configuration
public class TenantSuspensionConfiguration implements WebMvcConfigurer {

    /**
     * Fora da recusa: o ciclo da sessão (login, refresh, logout, {@code /me}, aceite de convite). O
     * login não leva token, então já passaria; os demais ficam de fora para o usuário do tenant suspenso
     * ainda conseguir sair — os dados, que é o que a suspensão corta, estão nos outros caminhos.
     */
    static final String[] EXCLUDED_PATHS = {"/api/auth/**"};

    private final boolean authEnabled;
    private final ObjectProvider<TenantControllerImpl> tenants;

    public TenantSuspensionConfiguration(@Value("${archflow.security.auth.enabled:false}") boolean authEnabled,
                                         ObjectProvider<TenantControllerImpl> tenants) {
        this.authEnabled = authEnabled;
        this.tenants = tenants;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // O cadastro é lido a cada requisição, e não copiado aqui: suspender e reativar valem na hora.
        registry.addInterceptor(new TenantSuspensionInterceptor(authEnabled, tenantId -> {
            TenantControllerImpl cadastro = tenants.getIfAvailable();
            return cadastro != null && cadastro.isSuspended(tenantId);
        })).addPathPatterns("/**").excludePathPatterns(EXCLUDED_PATHS);
    }
}
