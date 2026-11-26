package br.com.archflow.api.config;

import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.web.template.SpringTemplateController;
import br.com.archflow.api.web.workflow.SpringExecutionController;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.api.web.workflow.SpringWorkflowYamlController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A tabela do {@link WorkflowPermissionInterceptor} (RCHFLW-74) contra os controllers reais: toda
 * escrita mapeada sob fluxos/execuções exige uma permissão — um endpoint de escrita novo não nasce
 * aberto — e nenhuma leitura exige.
 */
@DisplayName("WorkflowPermissionInterceptor — tabela de permissões")
class WorkflowPermissionInterceptorTest {

    private record Rota(String method, String path) {
    }

    private static List<Rota> rotas(Class<?> controller) {
        RequestMapping base = controller.getAnnotation(RequestMapping.class);
        String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
        List<Rota> out = new ArrayList<>();
        for (Method m : controller.getDeclaredMethods()) {
            add(out, "GET", prefix, m.getAnnotation(GetMapping.class) == null ? null
                    : m.getAnnotation(GetMapping.class).value());
            add(out, "POST", prefix, m.getAnnotation(PostMapping.class) == null ? null
                    : m.getAnnotation(PostMapping.class).value());
            add(out, "PUT", prefix, m.getAnnotation(PutMapping.class) == null ? null
                    : m.getAnnotation(PutMapping.class).value());
            add(out, "PATCH", prefix, m.getAnnotation(PatchMapping.class) == null ? null
                    : m.getAnnotation(PatchMapping.class).value());
            add(out, "DELETE", prefix, m.getAnnotation(DeleteMapping.class) == null ? null
                    : m.getAnnotation(DeleteMapping.class).value());
        }
        return out;
    }

    private static void add(List<Rota> out, String method, String prefix, String[] values) {
        if (values == null) {
            return;
        }
        if (values.length == 0) {
            out.add(new Rota(method, prefix));
        }
        for (String v : values) {
            // {id} → um valor concreto: a tabela casa por segmento.
            out.add(new Rota(method, (prefix + v).replaceAll("\\{[^}]+}", "x")));
        }
    }

    @Test
    @DisplayName("toda escrita dos controllers de fluxo/execução exige permissão; leitura não")
    void cobreOsControllersReais() {
        List<Rota> todas = new ArrayList<>();
        todas.addAll(rotas(SpringWorkflowCrudController.class));
        todas.addAll(rotas(SpringWorkflowYamlController.class));
        todas.addAll(rotas(SpringExecutionController.class));
        rotas(AgUiController.class).stream().filter(r -> r.path().startsWith("/ag-ui/workflows")).forEach(todas::add);
        assertThat(todas).hasSizeGreaterThan(15);
        for (Rota r : todas) {
            Optional<String> exigida = WorkflowPermissionInterceptor.requiredPermission(r.method(), r.path());
            if ("GET".equals(r.method())) {
                assertThat(exigida).as(r.toString()).isEmpty();
            } else {
                assertThat(exigida).as(r.toString()).isPresent();
            }
        }
    }

    @Test
    @DisplayName("ação → permissão")
    void tabela() {
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/workflows"))
                .contains("workflow:create");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("PUT", "/api/workflows/x"))
                .contains("workflow:update");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/workflows/x/publish"))
                .contains("workflow:update");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("DELETE", "/api/workflows/x"))
                .contains("workflow:delete");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/workflows/x/execute"))
                .contains("workflow:execute");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/ag-ui/workflows/x"))
                .contains("workflow:execute");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/executions/x/cancel"))
                .contains("execution:manage");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/executions/x/resume"))
                .contains("execution:manage");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/templates/x/install"))
                .contains("workflow:create");
        // O cancelar da observabilidade é o mesmo cancelar: sem a regra, o VIEWER cancelava por aqui.
        assertThat(WorkflowPermissionInterceptor.requiredPermission(
                "POST", "/api/admin/observability/running/x/cancel")).contains("execution:manage");
        assertThat(WorkflowPermissionInterceptor.requiredPermission(
                "GET", "/api/admin/observability/running")).isEmpty();
        assertThat(WorkflowPermissionInterceptor.requiredPermission("GET", "/api/executions/x")).isEmpty();
        assertThat(WorkflowPermissionInterceptor.requiredPermission("OPTIONS", "/api/workflows/x")).isEmpty();
    }

    @Test
    @DisplayName("templates: só instalar (que grava um fluxo) exige workflow:create; o resto não grava")
    void templates() {
        for (Rota r : rotas(SpringTemplateController.class)) {
            Optional<String> exigida = WorkflowPermissionInterceptor.requiredPermission(r.method(), r.path());
            if (r.path().endsWith("/install")) {
                assertThat(exigida).as(r.toString()).contains("workflow:create");
            } else {
                // Inclui o POST /unsupported-node-types, que só calcula os nós sem executor.
                assertThat(exigida).as(r.toString()).isEmpty();
            }
        }
        assertThat(rotas(SpringTemplateController.class)).contains(new Rota("POST", "/api/templates/x/install"));
    }

    @Test
    @DisplayName("QA2 P1–P4: aprovações, /mcp, agentes/LLM, conversas, gatilhos, bases, arquivos, jobs, skills")
    void tabelaQa2() {
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/approvals/x"))
                .contains("execution:manage");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/archflow/approvals/x"))
                .contains("execution:manage");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/mcp")).contains("workflow:execute");
        for (String rota : List.of("/archflow/agents/x/invoke", "/archflow/events/message", "/ag-ui/agent",
                "/api/orchestration/run", "/api/agents/qp/quote", "/api/conversations", "/api/conversations/resume",
                "/api/conversations/x/messages", "/api/admin/triggers/x/fire")) {
            assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", rota)).as(rota)
                    .contains("workflow:execute");
        }
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/admin/triggers"))
                .contains("workflow:update");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/knowledge-bases"))
                .contains("workflow:update");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/files"))
                .contains("workflow:update");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/jobs"))
                .contains("execution:manage");
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/admin/skills/x/activate"))
                .contains("agent:manage");
        // A busca da base é um POST que só lê; o invoke do VendaX Core não é deste interceptor.
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/knowledge-bases/x/search"))
                .isEmpty();
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/agents/invoke")).isEmpty();
        assertThat(WorkflowPermissionInterceptor.requiredPermission("POST", "/api/agents/invoke/stream")).isEmpty();
    }

    /**
     * Escritas que de propósito não passam por papel nenhum, cada uma com o motivo. Uma escrita nova fora
     * desta lista e fora dos interceptors faz {@link #todaEscritaDaApiTemDono} falhar: ou ganha uma regra,
     * ou entra aqui com a justificativa.
     */
    private static final Set<Rota> SEM_PAPEL = Set.of(
            // Login/refresh/logout e aceite de convite: quem chama ainda não tem (ou está largando) a sessão.
            new Rota("POST", "/api/auth/login"), new Rota("POST", "/api/auth/refresh"),
            new Rota("POST", "/api/auth/logout"), new Rota("POST", "/api/auth/invitations/accept"),
            // Chaves de API do próprio usuário: o ApiKeyService limita os escopos ao que o papel do dono concede.
            new Rota("POST", "/api/apikeys"), new Rota("DELETE", "/api/apikeys/x"),
            // VendaX Core: chamada de máquina, fora do JWT, autenticada pela chave de serviço no controller.
            new Rota("POST", "/api/agents/invoke"), new Rota("POST", "/api/agents/invoke/stream"),
            new Rota("POST", "/api/agents/invoke/x/cancel"),
            // Cancelamento de chave com barra (RCHFLW-190): mesma chave de serviço, só repassa ao /cancel.
            new Rota("POST", "/api/agents/invoke/x"),
            // Assist (ADR-0004): caminho público, protegido pela chave estática X-ArchFlow-Key no controller.
            new Rota("POST", "/archflow/assist/explain-error"), new Rota("POST", "/archflow/assist/suggest-mapping"),
            new Rota("POST", "/archflow/assist/nl-to-flow"),
            // Ingestão de eventos do emissor standalone: fixa o tenant da identidade (RCHFLW-44).
            new Rota("POST", "/api/events/ingest"),
            // Só calcula os nós sem executor de um documento; não grava nada.
            new Rota("POST", "/api/templates/unsupported-node-types"));

    private static boolean casa(String[] padroes, String path) {
        PathContainer caminho = PathContainer.parsePath(path);
        return Arrays.stream(padroes).anyMatch(p -> PathPatternParser.defaultInstance.parse(p).matches(caminho));
    }

    @Test
    @DisplayName("toda escrita de todo controller da API tem papel exigido, ou está na lista justificada")
    void todaEscritaDaApiTemDono() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Rota> escritas = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("br.com.archflow.api")) {
            for (Rota r : rotas(Class.forName(bd.getBeanClassName()))) {
                if (!"GET".equals(r.method())) {
                    escritas.add(r);
                }
            }
        }
        assertThat(escritas).hasSizeGreaterThan(60);
        List<Rota> semDono = new ArrayList<>();
        for (Rota r : escritas) {
            boolean admin = casa(AdminSecurityConfiguration.ADMIN_PATHS, r.path())
                    || casa(AdminSecurityConfiguration.ADMIN_WRITE_PATHS, r.path());
            boolean permissao = casa(AdminSecurityConfiguration.WORKFLOW_PATHS, r.path())
                    && WorkflowPermissionInterceptor.requiredPermission(r.method(), r.path()).isPresent();
            boolean busca = r.equals(new Rota("POST", "/api/knowledge-bases/x/search"));
            if (!admin && !permissao && !busca && !SEM_PAPEL.contains(r)) {
                semDono.add(r);
            }
        }
        assertThat(semDono).as("escritas sem papel exigido").isEmpty();
    }

    @Test
    @DisplayName("papéis: as definições built-in de Role, com ou sem ROLE_, e admin concede tudo")
    void papeis() {
        assertThat(WorkflowPermissionInterceptor.permits(List.of("VIEWER"), "workflow:read")).isTrue();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("VIEWER"), "workflow:execute")).isFalse();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("executor"), "workflow:execute")).isTrue();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("EXECUTOR"), "workflow:update")).isFalse();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("ROLE_DESIGNER"), "workflow:delete")).isTrue();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("DESIGNER"), "execution:manage")).isFalse();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("VIEWER", "ADMIN"), "execution:manage")).isTrue();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("SUPERADMIN"), "workflow:delete")).isTrue();
        assertThat(WorkflowPermissionInterceptor.permits(List.of("EDITOR"), "workflow:update")).isFalse();
    }
}
