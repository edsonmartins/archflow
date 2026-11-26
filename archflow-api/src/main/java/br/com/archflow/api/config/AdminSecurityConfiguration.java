package br.com.archflow.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra o {@link AdminRoleInterceptor} nos paths de administração e o
 * {@link WorkflowPermissionInterceptor} nos de fluxos, execuções e demais escritas por papel (RCHFLW-74,
 * QA2 P1–P4).
 * Configuração separada de {@link WebMvcConfiguration} (CORS) para manter a
 * superfície de enforcement de papel isolada e testável.
 */
@Configuration
public class AdminSecurityConfiguration implements WebMvcConfigurer {

    /**
     * Paths que exigem role ADMIN/SUPERADMIN. O workspace entrou com RCHFLW-85: convidar, trocar papel
     * e remover passaram a mudar o acesso real do login — aberto a qualquer autenticado, um
     * visualizador se promoveria a ADMIN.
     */
    static final String[] ADMIN_PATHS = {
        "/api/admin/global/**", "/api/admin/tenants/**", "/api/admin/workspace/**",
        // QA2 P2: a configuração do Linktor grava o comando que o servidor executa (mcpCommand) e o destino
        // para onde a chave guardada é enviada; a do Brain Sentry, a URL e a chave. Abertas a qualquer
        // autenticado, um VIEWER executava código no servidor e desviava a credencial. A inbox e o
        // escalar falam com o Linktor com essa mesma chave; a UI já só os mostra ao admin.
        "/api/admin/linktor", "/api/admin/linktor/**", "/api/admin/brainsentry", "/api/admin/brainsentry/**"
    };

    /**
     * Paths cujas escritas (não as leituras) exigem ADMIN/SUPERADMIN. O catálogo de extensões é um só
     * para a plataforma inteira: instalar ou remover ali muda o que todos os tenants veem.
     */
    static final String[] ADMIN_WRITE_PATHS = {"/api/extensions", "/api/extensions/**"};

    /** Paths cujas escritas exigem a permissão do papel ({@link WorkflowPermissionInterceptor}). */
    static final String[] WORKFLOW_PATHS = {
        "/api/workflows", "/api/workflows/**", "/api/executions", "/api/executions/**", "/ag-ui/workflows/**",
        "/api/templates/*/install", "/api/admin/observability/running/*/cancel",
        // QA2 P1, P3 e P4 — a tabela de cada um está em WorkflowPermissionInterceptor.requiredPermission.
        // /api/agents/invoke/** fica de fora: é o VendaX Core, autenticado pela chave de serviço.
        "/api/approvals/**", "/archflow/approvals/**", "/mcp", "/archflow/agents/**", "/archflow/events/**",
        "/ag-ui/agent", "/api/orchestration/**", "/api/agents/qp/**", "/api/conversations",
        "/api/conversations/**", "/api/admin/triggers", "/api/admin/triggers/**", "/api/knowledge-bases",
        "/api/knowledge-bases/**", "/api/files", "/api/files/**", "/api/jobs", "/api/jobs/**",
        "/api/admin/skills/**"
    };

    private final boolean authEnabled;

    public AdminSecurityConfiguration(
            @Value("${archflow.security.auth.enabled:false}") boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AdminRoleInterceptor(authEnabled))
                .addPathPatterns(ADMIN_PATHS);
        registry.addInterceptor(new AdminRoleInterceptor(authEnabled, true))
                .addPathPatterns(ADMIN_WRITE_PATHS);
        registry.addInterceptor(new WorkflowPermissionInterceptor(authEnabled))
                .addPathPatterns(WORKFLOW_PATHS);
    }
}
