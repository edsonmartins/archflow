package br.com.archflow.api.config;

import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.web.template.SpringTemplateController;
import br.com.archflow.api.web.workflow.SpringExecutionController;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.api.web.workflow.SpringWorkflowYamlController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
