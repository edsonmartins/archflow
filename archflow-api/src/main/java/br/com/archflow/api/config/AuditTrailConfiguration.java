package br.com.archflow.api.config;

import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.admin.store.GlobalConfigStore;
import br.com.archflow.api.apikey.impl.ApiKeyControllerImpl;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.auth.impl.AuthControllerImpl;
import br.com.archflow.observability.audit.AuditRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring da trilha de auditoria (fase 5.6) e do store de config admin (2.5)
 * nos controllers.
 *
 * <p>Os beans dos controllers são declarados em
 * {@code ArchflowBeanConfiguration} com os construtores históricos; para não
 * tocar naquela configuração, a injeção do {@link AuditTrail}, do
 * {@link GlobalConfigStore} e do leitor de {@link AuditRepository} é feita por
 * setter num {@link SmartInitializingSingleton} — roda depois que todos os
 * singletons existem e antes do contexto atender requests.
 */
@Configuration
public class AuditTrailConfiguration {

    /**
     * Produtor central de eventos de auditoria. O {@link AuditRepository} é
     * resolvido via provider a cada evento: presente apenas quando
     * {@code archflow.persistence.jdbc.enabled=true} (ou quando o deployer
     * declara um bean próprio); ausente → no-op, nunca lança.
     */
    @Bean
    public AuditTrail auditTrail(ObjectProvider<AuditRepository> auditRepositories) {
        return new AuditTrail(auditRepositories::getIfAvailable);
    }

    @Bean
    public SmartInitializingSingleton auditTrailWiring(
            AuditTrail auditTrail,
            ObjectProvider<AuditRepository> auditRepositories,
            ObjectProvider<AuthControllerImpl> authControllers,
            ObjectProvider<ApiKeyControllerImpl> apiKeyControllers,
            ObjectProvider<TenantControllerImpl> tenantControllers,
            ObjectProvider<GlobalConfigControllerImpl> globalConfigControllers,
            ObjectProvider<GlobalConfigStore> globalConfigStores) {
        return () -> {
            authControllers.ifAvailable(c -> c.setAuditTrail(auditTrail));
            apiKeyControllers.ifAvailable(c -> c.setAuditTrail(auditTrail));
            tenantControllers.ifAvailable(c -> c.setAuditTrail(auditTrail));
            globalConfigControllers.ifAvailable(c -> {
                c.setAuditTrail(auditTrail);
                c.setAuditRepository(auditRepositories::getIfAvailable);
                globalConfigStores.ifAvailable(c::setStore);
            });
        };
    }
}
