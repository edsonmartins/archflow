package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKeyScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApiKeyScopeRequirement — escopo exigido por endpoint")
class ApiKeyScopeRequirementTest {

    private static Optional<ApiKeyScope> exige(String method, String path) {
        return ApiKeyScopeRequirement.requiredScope(method, path);
    }

    @Test
    @DisplayName("os caminhos que o Publicar ensina exigem WORKFLOW_EXECUTE")
    void caminhosDoPublicar() {
        assertThat(exige("POST", "/api/workflows/wf-1/execute")).contains(ApiKeyScope.WORKFLOW_EXECUTE);
        assertThat(exige("POST", "/ag-ui/workflows/wf-1")).contains(ApiKeyScope.WORKFLOW_EXECUTE);
        assertThat(exige("POST", "/mcp")).contains(ApiKeyScope.WORKFLOW_EXECUTE);
    }

    @Test
    @DisplayName("workflows e execuções: cada verbo com o seu escopo")
    void workflowsEExecucoes() {
        assertThat(exige("GET", "/api/workflows")).contains(ApiKeyScope.WORKFLOW_READ);
        assertThat(exige("GET", "/api/workflows/wf-1/yaml")).contains(ApiKeyScope.WORKFLOW_READ);
        assertThat(exige("POST", "/api/workflows")).contains(ApiKeyScope.WORKFLOW_CREATE);
        assertThat(exige("PUT", "/api/workflows/wf-1")).contains(ApiKeyScope.WORKFLOW_UPDATE);
        assertThat(exige("PATCH", "/api/workflows/wf-1/status")).contains(ApiKeyScope.WORKFLOW_UPDATE);
        assertThat(exige("POST", "/api/workflows/wf-1/publish")).contains(ApiKeyScope.WORKFLOW_UPDATE);
        assertThat(exige("DELETE", "/api/workflows/wf-1")).contains(ApiKeyScope.WORKFLOW_DELETE);
        assertThat(exige("GET", "/api/executions")).contains(ApiKeyScope.EXECUTION_READ);
        assertThat(exige("GET", "/api/executions/e-1")).contains(ApiKeyScope.EXECUTION_READ);
        assertThat(exige("POST", "/api/executions/e-1/cancel")).contains(ApiKeyScope.EXECUTION_MANAGE);
    }

    @Test
    @DisplayName("opensAnyEndpoint é exatamente o conjunto de escopos que a tabela devolve (mais ADMIN)")
    void escoposQueAbremAlgumEndpoint() {
        java.util.Set<ApiKeyScope> devolvidos = java.util.EnumSet.noneOf(ApiKeyScope.class);
        for (String metodo : List.of("GET", "POST", "PUT", "PATCH", "DELETE")) {
            for (String caminho : List.of("/api/workflows", "/api/workflows/wf-1", "/api/workflows/wf-1/execute",
                    "/api/workflows/wf-1/yaml", "/api/executions", "/api/executions/e-1",
                    "/api/executions/e-1/cancel", "/ag-ui/workflows/wf-1", "/mcp")) {
                exige(metodo, caminho).ifPresent(devolvidos::add);
            }
        }
        for (ApiKeyScope escopo : ApiKeyScope.values()) {
            assertThat(ApiKeyScopeRequirement.opensAnyEndpoint(escopo)).as(escopo.name())
                    .isEqualTo(escopo.isAdmin() || devolvidos.contains(escopo));
        }
        assertThat(ApiKeyScopeRequirement.opensAnyEndpoint(ApiKeyScope.METRICS_READ)).isFalse();
    }

    @Test
    @DisplayName("o que ninguém abriu para chaves não é coberto por escopo nenhum")
    void foraDaTabela() {
        for (String[] caso : List.of(
                new String[] {"GET", "/api/apikeys"},
                new String[] {"POST", "/api/apikeys"},
                new String[] {"GET", "/api/admin/workspace/users"},
                new String[] {"GET", "/api/admin/global/usage"},
                new String[] {"POST", "/api/approvals/r-1/decision"},
                new String[] {"POST", "/ag-ui/agent"},
                new String[] {"GET", "/mcp"},
                new String[] {"DELETE", "/api/workflows"})) {
            assertThat(exige(caso[0], caso[1])).as(caso[0] + " " + caso[1]).isEmpty();
        }
    }

    @Test
    @DisplayName("caminho fora da forma canônica não herda o escopo da árvore em que parece estar")
    void caminhoNaoCanonico() {
        for (String path : List.of(
                "/api/workflows/x/../../apikeys",
                "/api/workflows/%2e%2e/apikeys",
                "/api/workflows//x",
                "/api/workflows/x;jsessionid=1",
                "/api/workflows/./x",
                "/api/workflows/x/..")) {
            assertThat(exige("GET", path)).as(path).isEmpty();
        }
    }
}
