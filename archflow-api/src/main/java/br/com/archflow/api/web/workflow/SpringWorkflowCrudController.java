package br.com.archflow.api.web.workflow;

import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.flow.RunInputSeeding;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.CanvasAnnotation;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * In-memory CRUD controller for workflows.
 *
 * <p>Serves the frontend's workflow list/editor. Stores workflow JSON documents
 * in memory — suitable for development and demos. In production, replace with
 * a database-backed implementation.
 *
 * <p>Tudo aqui age só dentro do tenant da requisição ({@link #tenant()}): um workflow de outro tenant
 * responde 404, como se não existisse (RCHFLW-73).
 */
@RestController
@RequestMapping("/api/workflows")
public class SpringWorkflowCrudController {

    private final WorkflowRuntimeStore store;
    private final WorkflowDeserializer deserializer;
    private final FlowEngine flowEngine;
    private final FlowRepository flowRepository;
    private final AuditTrail auditTrail;
    /** Nulo numa instalação sem MCP: o nó falha dizendo isso, e o resto do fluxo segue existindo. */
    private final McpAgentHost mcpAgentHost;

    public SpringWorkflowCrudController(WorkflowRuntimeStore store,
                                        WorkflowDeserializer deserializer,
                                        FlowEngine flowEngine,
                                        FlowRepository flowRepository,
                                        AuditTrail auditTrail,
                                        McpAgentHost mcpAgentHost) {
        this.store = store;
        this.deserializer = deserializer;
        this.flowEngine = flowEngine;
        this.flowRepository = flowRepository;
        this.auditTrail = auditTrail != null ? auditTrail : AuditTrail.noop();
        this.mcpAgentHost = mcpAgentHost;
    }

    /**
     * Tenant da requisição, resolvido no servidor (JWT, personificação verificada ou o padrão) — nunca
     * do corpo. Lido no início de cada handler, na thread do servlet.
     */
    private static String tenant() {
        return TenantContext.currentTenantId();
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        String tenantId = tenant();
        // Uma leitura das versões em produção para a lista inteira, e não duas consultas por fluxo.
        Map<String, Integer> published = store.productionVersionNumbers(tenantId);
        return store.workflows(tenantId).stream()
                .map(wf -> toSummary(published, wf))
                .sorted(Comparator.comparing((Map<String, Object> m) ->
                        m.getOrDefault("updatedAt", "").toString()).reversed())
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        var wf = store.getWorkflow(tenant(), id);
        return wf != null ? ResponseEntity.ok(wf) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        var created = new LinkedHashMap<>(body);
        var id = "wf-" + UUID.randomUUID().toString().substring(0, 8);
        created.put("id", id);
        created.put("status", "draft");
        created.put("updatedAt", Instant.now().toString());
        created.putIfAbsent("steps", List.of());
        created.putIfAbsent("configuration", Map.of());
        store.putWorkflow(tenant(), id, created);
        auditTrail.record(AuditAction.CREATE, "workflow", id, true, null,
                Map.of("name", workflowName(created)));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Grava o documento inteiro por cima do atual.
     *
     * <p>Controle de concorrência otimista (RCHFLW-125): quem manda {@code If-Match} com o {@code updatedAt} que
     * leu só grava se o fluxo continua nessa versão; se outra sessão gravou no meio, a resposta é 409 com o
     * {@code updatedAt} atual, e o editor pergunta se recarrega ou sobrescreve. Antes o segundo Salvar apagava
     * em silêncio a mudança do primeiro. 409, e não o 412 que o HTTP prevê para If-Match: o conflito é de
     * conteúdo, e é o código que o editor trata. Sem o cabeçalho a gravação é a de sempre — o VendaX e os
     * scripts que fazem PUT direto não mandam versão e não podem passar a falhar.
     *
     * <p>A conferência e a gravação são uma operação só do store ({@link WorkflowRuntimeStore#updateWorkflow}),
     * serializada com o PATCH de status, o Salvar YAML e a exclusão — no JDBC pela trava da linha, também entre
     * réplicas. Antes era uma trava deste controller, que o PATCH de status e o DELETE não pegavam: um PATCH no
     * meio apagava o Salvar confirmado (RCHFLW-173), e um Salvar logo depois de uma exclusão recriava o fluxo
     * (RCHFLW-172) — agora ele recebe 404.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody Map<String, Object> body,
                                                      @RequestHeader(value = "If-Match", required = false)
                                                      String ifMatch) {
        var result = store.updateWorkflow(tenant(), id, versaoEsperada(ifMatch), atual -> {
            var updated = new LinkedHashMap<>(body);
            updated.put("id", id);
            updated.put("updatedAt", Instant.now().toString());
            updated.putIfAbsent("status", "draft");
            updated.putIfAbsent("steps", List.of());
            updated.putIfAbsent("configuration", Map.of());
            return updated;
        });
        ResponseEntity<Map<String, Object>> recusa = recusaDe(result);
        if (recusa != null) return recusa;
        auditTrail.record(AuditAction.UPDATE, "workflow", id, true, null,
                Map.of("name", workflowName(result.document())));
        return ResponseEntity.ok(result.document());
    }

    /**
     * A resposta de uma alteração que não gravou — 404 ou o 409 de conflito que o editor trata
     * ({@code WORKFLOW_CONFLICT}, com o {@code updatedAt} atual) —, ou {@code null} quando ela gravou. Uma regra
     * só para o PUT, o PATCH de status, o Salvar YAML e o Publicar.
     */
    static ResponseEntity<Map<String, Object>> recusaDe(WorkflowRuntimeStore.Update result) {
        return switch (result.outcome()) {
            case UPDATED -> null;
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case CONFLICT -> conflito(result.currentUpdatedAt());
        };
    }

    static ResponseEntity<Map<String, Object>> conflito(Object updatedAtAtual) {
        Map<String, Object> conflito = new LinkedHashMap<>();
        conflito.put("error", "Workflow was changed elsewhere since it was loaded");
        conflito.put("code", "WORKFLOW_CONFLICT");
        conflito.put("updatedAt", updatedAtAtual);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(conflito);
    }

    /**
     * O {@code updatedAt} pedido no {@code If-Match}, sem as aspas da entity tag (e sem o {@code W/}); nulo
     * quando não há pré-condição — cabeçalho ausente, vazio ou {@code *} (qualquer versão serve).
     */
    static String versaoEsperada(String ifMatch) {
        if (ifMatch == null) return null;
        String valor = ifMatch.trim();
        if (valor.startsWith("W/")) valor = valor.substring(2).trim();
        if (valor.length() >= 2 && valor.startsWith("\"") && valor.endsWith("\"")) {
            valor = valor.substring(1, valor.length() - 1);
        }
        return valor.isEmpty() || "*".equals(valor) ? null : valor;
    }

    /**
     * Exclui o fluxo e mantém o histórico de execuções (RCHFLW-122, ver
     * {@link WorkflowRuntimeStore#deleteWorkflow}).
     *
     * <p>409 enquanto houver execução do fluxo em andamento ou pausada: quem decide a aprovação pendente a
     * decidiria para um fluxo que já sumiu da lista. A resposta diz quais execuções — cancelá-las ou decidir a
     * aprovação libera a exclusão. A recusa vem do próprio store, na mesma operação que exclui: uma consulta
     * antes dela deixava a execução criada entre as duas escapar.</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        String tenantId = tenant();
        Map<String, Object> documento = store.getWorkflow(tenantId, id);
        if (documento == null) return ResponseEntity.notFound().build();
        WorkflowRuntimeStore.Deletion exclusao = store.deleteWorkflow(tenantId, id);
        switch (exclusao.outcome()) {
            case NOT_FOUND -> {
                return ResponseEntity.notFound().build();
            }
            case HAS_ACTIVE_EXECUTIONS -> {
                List<String> ativas = exclusao.activeExecutions();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", motivoDaExclusaoRecusada(tenantId, workflowName(documento), ativas));
                body.put("code", "WORKFLOW_HAS_ACTIVE_EXECUTIONS");
                body.put("executions", ativas);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            default -> {
                auditTrail.record(AuditAction.DELETE, "workflow", id, true, null, null);
                return ResponseEntity.noContent().build();
            }
        }
    }

    /**
     * A recusa da exclusão, dizendo para cada execução o que de fato a libera (RCHFLW-188, item 4). A frase antiga
     * mandava cancelar qualquer uma, mas a execução pausada na aprovação não se cancela — o cancel devolve 200 com
     * ela ainda PAUSED, e o detalhe da execução nem tem a ação. A pausada sai quando a aprovação é decidida
     * (rejeitar a encerra); a em andamento, quando termina ou é cancelada.
     */
    private String motivoDaExclusaoRecusada(String tenantId, String nome, List<String> ativas) {
        List<String> pausadas = new ArrayList<>();
        List<String> rodando = new ArrayList<>();
        for (String execucao : ativas) {
            Map<String, Object> registro = store.getExecution(tenantId, execucao);
            boolean pausada = registro != null && "PAUSED".equals(String.valueOf(registro.get("status")));
            (pausada ? pausadas : rodando).add(execucao);
        }
        StringBuilder motivo = new StringBuilder("O fluxo \"").append(nome).append("\" tem ");
        if (!pausadas.isEmpty()) {
            motivo.append(pausadas.size() == 1 ? "uma execução" : pausadas.size() + " execuções")
                    .append(" aguardando aprovação (").append(String.join(", ", pausadas)).append(")");
        }
        if (!rodando.isEmpty()) {
            motivo.append(pausadas.isEmpty() ? "" : " e ")
                    .append(rodando.size() == 1 ? "uma execução" : rodando.size() + " execuções")
                    .append(" em andamento (").append(String.join(", ", rodando)).append(")");
        }
        motivo.append(".");
        if (!pausadas.isEmpty()) {
            motivo.append(" A execução pausada só libera a exclusão quando a aprovação pendente é decidida, em "
                    + "Aprovações — rejeitar encerra a execução.");
        }
        if (!rodando.isEmpty()) {
            motivo.append(" A execução em andamento libera a exclusão quando termina ou é cancelada.");
        }
        return motivo.toString();
    }

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<WorkflowVersionRecord>> versions(@PathVariable String id) {
        String tenantId = tenant();
        if (!store.hasWorkflow(tenantId, id)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(store.workflowVersions(tenantId, id));
    }

    @GetMapping("/{id}/versions/{versionId}")
    public ResponseEntity<WorkflowVersionRecord> version(@PathVariable String id,
                                                          @PathVariable String versionId) {
        var version = store.getWorkflowVersion(tenant(), id, versionId);
        return version != null ? ResponseEntity.ok(version) : ResponseEntity.notFound().build();
    }

    /** Publica o rascunho gravado, sem pré-condição — o {@code POST} sem {@code If-Match}. */
    public ResponseEntity<Map<String, Object>> publish(String id, Map<String, Object> body) {
        return publish(id, body, null);
    }

    /**
     * Publishes the current draft as an immutable version and deploys it to the
     * requested environment (PRODUCTION by default).
     *
     * <p>Com {@code If-Match}, publica só o rascunho nessa versão — 409 {@code WORKFLOW_CONFLICT} se outra
     * sessão gravou depois (RCHFLW-146): o editor salva o que está na tela e publica exatamente isso. Sem o
     * cabeçalho, publica o rascunho gravado, como sempre. Excluído antes, 404 (RCHFLW-188, item 20; antes, 500 de
     * um NPE). A versão e a implantação são uma operação só do store
     * ({@link WorkflowRuntimeStore#publishAndDeployWorkflow}): em duas, uma publicação mais velha podia implantar
     * depois de uma mais nova e deixar PRODUCTION nela, com a resposta da mais nova anunciando a versão dela.</p>
     */
    @PostMapping("/{id}/publish")
    public ResponseEntity<Map<String, Object>> publish(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        String comment = body == null ? null : Objects.toString(body.get("comment"), null);
        String environment = body == null
                ? "PRODUCTION"
                : Objects.toString(body.get("environment"), "PRODUCTION");
        String tenantId = tenant();
        WorkflowRuntimeStore.Publication publication;
        try {
            publication = store.publishAndDeployWorkflow(tenantId, id, comment, versaoEsperada(ifMatch), environment);
        } catch (WorkflowRuntimeStore.WorkflowConflictException e) {
            return conflito(e.currentUpdatedAt());
        }
        if (publication == null || publication.deployment() == null) return ResponseEntity.notFound().build();
        var version = publication.version();
        var deployment = publication.deployment();
        auditTrail.record(AuditAction.UPDATE, "workflow", id, true, null,
                Map.of("operation", "publish", "versionId", version.id(),
                        "environment", deployment.environment()));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "version", version,
                "deployment", deployment));
    }

    /** Deploys an older version too, making this endpoint the rollback primitive. */
    @PutMapping("/{id}/deployments/{environment}")
    public ResponseEntity<WorkflowDeploymentRecord> deploy(
            @PathVariable String id,
            @PathVariable String environment,
            @RequestBody Map<String, Object> body) {
        String versionId = Objects.toString(body.get("versionId"), "");
        var deployment = store.deployWorkflow(tenant(), id, environment, versionId);
        if (deployment == null) return ResponseEntity.notFound().build();
        auditTrail.record(AuditAction.UPDATE, "workflow", id, true, null,
                Map.of("operation", "deploy", "versionId", versionId,
                        "environment", deployment.environment()));
        return ResponseEntity.ok(deployment);
    }

    @GetMapping("/{id}/deployments/{environment}")
    public ResponseEntity<WorkflowDeploymentRecord> deployment(
            @PathVariable String id, @PathVariable String environment) {
        var deployment = store.getDeployment(tenant(), id, environment);
        return deployment != null
                ? ResponseEntity.ok(deployment)
                : ResponseEntity.notFound().build();
    }

    /** Ciclo de vida do workflow (draft → active → archived). */
    private static final java.util.Set<String> VALID_STATUSES =
            java.util.Set.of("draft", "active", "archived");

    /**
     * Troca só o status, sobre o documento que estiver gravado no instante da troca, pela mesma operação
     * atômica do PUT ({@link WorkflowRuntimeStore#updateWorkflow}). Antes lia e regravava o documento inteiro
     * fora de qualquer trava: um Salvar confirmado no meio era desfeito, ou desfazia a troca, com as duas
     * respostas em 200 (RCHFLW-173); e uma troca durante a exclusão recriava o fluxo (RCHFLW-172). O
     * {@code updatedAt} novo faz um Salvar com a versão de antes da troca levar 409, como qualquer outra
     * gravação. Sem pré-condição própria: o "Ativar" da lista não tem versão para mandar.
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> setStatus(@PathVariable String id,
                                                         @RequestBody Map<String, Object> body) {
        String tenantId = tenant();
        if (!store.hasWorkflow(tenantId, id)) return ResponseEntity.notFound().build();
        String status = String.valueOf(body.get("status")).toLowerCase(Locale.ROOT);
        if (!VALID_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid status: " + status + " (expected draft|active|archived)"));
        }
        var result = store.updateWorkflow(tenantId, id, null, atual -> {
            atual.put("status", status);
            atual.put("updatedAt", Instant.now().toString());
            return atual;
        });
        ResponseEntity<Map<String, Object>> recusa = recusaDe(result);
        if (recusa != null) return recusa;
        auditTrail.record(AuditAction.UPDATE, "workflow", id, true, null,
                Map.of("status", status));
        return ResponseEntity.ok(result.document());
    }

    @PostMapping("/{id}/execute")
    public ResponseEntity<Map<String, Object>> execute(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> input) {
        // Um tenant só, do começo ao fim: o mesmo que filtra o workflow é o dono da execução e o do
        // contexto do motor (estado, aprovações, chaves).
        String tenantId = tenant();
        var draft = store.getWorkflow(tenantId, id);
        if (draft == null) return ResponseEntity.notFound().build();
        var version = productionVersion(tenantId, id);
        // Compatibility window: legacy workflows without a deployment still run
        // their draft. Every new publish pins subsequent executions to a snapshot.
        var workflow = version == null ? draft : version.document();
        String workflowVersionId = version == null ? null : version.id();

        var execution = store.createExecution(tenantId, id, workflowName(workflow), version);
        String executionId = String.valueOf(execution.get("id"));

        // Deserialize the stored JSON into an executable Flow and submit it to the
        // async engine (design-0005 step 2). The flow id is the executionId so
        // concurrent runs of the same workflow don't collide in the engine.
        Map<String, Object> flowJson = new HashMap<>(workflow);
        flowJson.put("id", executionId);
        Flow flow = deserializer.toFlow(flowJson);
        // Register the flow so a paused run can be resumed later (design-0005 step 5).
        flowRepository.save(flow);

        // O tenant é o da requisição (JWT, personificação verificada ou o padrão), resolvido no
        // servidor. Com null o motor gravava o estado — e as aprovações dele — num tenant que
        // nenhuma fila consultava (RCHFLW-13). Uma chave "tenantId" no input continua sendo só
        // variável: quem chama a API não escolhe o tenant pelo corpo.
        ExecutionContext ctx = new DefaultExecutionContext(
                tenantId, "runner", executionId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        // Cada chave vira variável e, sem chave "input", o objeto inteiro vira o input — a mesma regra
        // do AG-UI, para a mesma entrada dar o mesmo resultado pelo editor e pela lista/API.
        RunInputSeeding.seedVariables(ctx, input);
        // Depois do input, e não antes: quem escreve por último vence, e uma variável de entrada
        // chamada "mcp.host" seria dado de quem chama a API sobrescrevendo infraestrutura — o nó
        // passaria a falar com o servidor que ela apontasse.
        McpAgentHost.inject(ctx, mcpAgentHost);

        auditTrail.record(AuditAction.WORKFLOW_EXECUTE, "workflow", id, true, null,
                Map.of("executionId", executionId));

        // Fire-and-track: return immediately, update status when the run finishes.
        flowEngine.execute(flow, ctx).whenComplete((result, err) ->
                store.completeExecution(executionId, statusOf(result, err), errorOf(err)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("executionId", executionId);
        body.put("status", "RUNNING");
        body.put("workflowId", id);
        body.put("workflowVersionId", workflowVersionId);
        // Quem chama a API vê o que rodou (RCHFLW-89): o número da versão publicada, ou a marca do
        // rascunho quando o fluxo ainda não tem versão em produção — os mesmos campos do registro.
        body.put("workflowDraft", execution.get("workflowDraft"));
        body.put("workflowVersionNumber", execution.get("workflowVersionNumber"));
        body.put("startedAt", execution.get("startedAt"));
        return ResponseEntity.ok(body);
    }

    private static String statusOf(FlowResult result, Throwable err) {
        if (err != null || result == null) {
            return "FAILED";
        }
        return result.getStatus() != null ? result.getStatus().name() : "COMPLETED";
    }

    /**
     * A razão da falha, não o invólucro: o motor embrulha a exceção duas vezes ({@code CompletionException}
     * → {@code FlowEngineException("Error executing flow: …")}), e ler um nível só gravava
     * "Error executing flow: exec-…" no lugar, por exemplo, da recusa de um nó sem execução (RCHFLW-11).
     * Mesma regra do {@code rootMessage} do {@code WorkflowMcpServer}.
     */
    private static String errorOf(Throwable err) {
        if (err == null) {
            return null;
        }
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    // ── helpers ──────────────────────────────────────────────────

    /**
     * A versão que o {@code /execute} roda: a implantada em PRODUCTION, ou {@code null} quando não há
     * implantação (ou ela aponta para uma versão que não existe) e roda o rascunho. Uma regra só para o
     * execute e para a lista: o selo do card diz o que o Rodar executa (RCHFLW-132).
     */
    private WorkflowVersionRecord productionVersion(String tenantId, String workflowId) {
        var deployment = store.getDeployment(tenantId, workflowId, "PRODUCTION");
        return deployment == null ? null : store.getWorkflowVersion(tenantId, workflowId, deployment.versionId());
    }

    private Map<String, Object> toSummary(Map<String, Integer> published, Map<String, Object> wf) {
        // Null-safe accessors: a malformed workflow JSON document (e.g.
        // metadata stored as a string instead of a map) would previously
        // ClassCastException through the controller and return 500. Fall
        // back to safe defaults instead.
        Map<String, Object> meta = asMap(wf.get("metadata"));
        // Anotações do canvas não são passos: "3 passos" não pode virar 4 por causa de uma nota.
        int stepCount = (int) asList(wf.get("steps")).stream()
                .filter(step -> !(step instanceof Map<?, ?> m && CanvasAnnotation.isAnnotation(m)))
                .count();
        // Map.of recusa nulo, e getOrDefault devolve o nulo quando a chave existe — o "Salvar YAML" grava
        // metadata com description/author nulos, e um único fluxo assim derrubava a lista do tenant (500).
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", valorOu(wf, "id", ""));
        summary.put("name", valorOu(meta, "name", "Untitled"));
        summary.put("description", valorOu(meta, "description", ""));
        summary.put("version", valorOu(meta, "version", "1.0.0"));
        // "status" é o rótulo do ciclo de vida (draft/active/archived) que o "Ativar" troca: nada na execução
        // o lê. O que o Rodar executa é a versão em PRODUCTION — "Publicar" no editor —, e o card mostrava o
        // rótulo como se fosse isso: "Rascunho" num fluxo com a v1 em produção, "Ativo" num que rodava o
        // rascunho (RCHFLW-132). publishedVersion é o número da versão que o Rodar executa, ou null quando
        // ele roda o rascunho — a mesma regra do productionVersion do /execute.
        summary.put("status", valorOu(wf, "status", "draft"));
        summary.put("publishedVersion", published.get(String.valueOf(valorOu(wf, "id", ""))));
        summary.put("updatedAt", valorOu(wf, "updatedAt", ""));
        summary.put("stepCount", stepCount);
        return summary;
    }

    /** O valor da chave, ou o padrão quando ela falta <b>ou</b> é nula. */
    private static Object valorOu(Map<String, Object> mapa, String chave, Object padrao) {
        Object valor = mapa.get(chave);
        return valor != null ? valor : padrao;
    }

    private String workflowName(Map<String, Object> workflow) {
        Map<String, Object> metadata = asMap(workflow.get("metadata"));
        Object name = metadata.get("name");
        return name != null ? name.toString() : "Untitled";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            // Accepting a Map<?,?> at runtime is safe because we only read
            // via Object keys/values below; the unchecked cast suppresses
            // a javac warning but never produces a ClassCastException.
            return (Map<String, Object>) raw;
        }
        return Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> l ? l : List.of();
    }
}
