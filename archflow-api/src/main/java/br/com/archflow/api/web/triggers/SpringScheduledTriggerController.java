package br.com.archflow.api.web.triggers;

import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.triggers.ScheduledTriggerController;
import br.com.archflow.api.triggers.ScheduledTriggerController.TriggerConflictException;
import br.com.archflow.api.triggers.ScheduledTriggerController.TriggerRejectedException;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.observability.audit.AuditAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP binding for {@link ScheduledTriggerController}.
 *
 * <p>O tenant de cada gatilho vem da identidade ({@link TenantScope}, RCHFLW-103). Antes o
 * {@code tenantId} do corpo era gravado como veio, e qualquer autenticado — um VIEWER inclusive —
 * agendava invocações de agente em nome de outro tenant. Agora:</p>
 *
 * <ul>
 *   <li>criar e editar: o tenant do gatilho é o do token; outro só com o papel ADMIN/SUPERADMIN
 *       (403 sem ele), e {@code "all"} não é um tenant (400);</li>
 *   <li>listar: o recorte de {@link TenantScope#forListing} — o tenant do token, ou {@code "all"} /
 *       outro tenant para o admin, como na fila de aprovações;</li>
 *   <li>ler, editar, apagar e disparar um gatilho existente: só quem pode agir no tenant dele; para os
 *       outros ele não existe (404), sem confirmar que o id é válido.</li>
 *   <li>criar com um {@code id} escolhido pelo cliente que já está em uso: 409, nada é gravado. O
 *       registro guarda por id, num espaço só para todos os tenants, e gravava por cima — um VIEWER do
 *       A trocava o gatilho do B pelo seu. O 409 diz que o id está tomado, mas não de quem nem o que
 *       ele faz, e vale igual para o id do próprio tenant: sobrescrever é editar, e editar é o PUT.</li>
 *   <li>editar com o {@code tenantId} em branco: o gatilho fica no tenant em que já estava. Antes o
 *       branco valia o tenant de quem edita, e o admin que mudava só o cron de um gatilho do B pela
 *       API o movia, sem querer, para o próprio tenant. (Omitido, o corpo nem desserializa: o DTO
 *       exige o campo.)</li>
 * </ul>
 *
 * <p>Com a autenticação desligada (dev), tudo passa como antes.</p>
 *
 * <p>RCHFLW-191: as recusas de validação vêm com um {@code code} ({@code TRIGGER_NAME_REQUIRED},
 * {@code TRIGGER_AGENT_UNKNOWN}, {@code TRIGGER_CRON_INVALID}, {@code TRIGGER_CRON_NEVER_FIRES},
 * {@code TRIGGER_BODY_INVALID}) que a tela traduz; o PUT aceita {@code If-Match} com o {@code updatedAt} lido e
 * responde 409 {@code TRIGGER_CONFLICT} quando outra gravação veio no meio (o padrão do Salvar de fluxo,
 * RCHFLW-125); e criar, editar, excluir e disparar vão para a trilha de auditoria, como os fluxos.</p>
 */
@RestController
@RequestMapping("/api/admin/triggers")
public class SpringScheduledTriggerController {

    /** Tipo do recurso na trilha de auditoria. */
    static final String AUDIT_RESOURCE = "trigger";

    private final ScheduledTriggerController delegate;
    private final TenantScope tenantScope;
    private final AuditTrail auditTrail;

    public SpringScheduledTriggerController(ScheduledTriggerController delegate, TenantScope tenantScope) {
        this(delegate, tenantScope, AuditTrail.noop());
    }

    @Autowired
    public SpringScheduledTriggerController(ScheduledTriggerController delegate, TenantScope tenantScope,
                                            AuditTrail auditTrail) {
        this.delegate = delegate;
        this.tenantScope = tenantScope;
        this.auditTrail = auditTrail != null ? auditTrail : AuditTrail.noop();
    }

    @GetMapping
    public List<ScheduledTriggerDto> list(@RequestParam(required = false) String tenantId) {
        String recorte = tenantScope.forItem(tenantId);
        return delegate.list().stream()
                .filter(trigger -> TenantScope.covers(recorte, trigger.tenantId()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(alcancavel(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * {@code synchronized}: a checagem de id em uso e a criação são um passo só para os POSTs, senão
     * dois com o mesmo id passavam ambos pela checagem e o segundo gravava por cima do primeiro.
     */
    @PostMapping
    public synchronized ResponseEntity<?> create(@RequestBody ScheduledTriggerDto dto) {
        try {
            ScheduledTriggerDto doTenant = doTenantDaIdentidade(dto);
            if (dto.id() != null && !dto.id().isBlank() && existe(dto.id())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Trigger id already in use: " + dto.id()));
            }
            ScheduledTriggerDto criado = delegate.create(doTenant);
            auditar(AuditAction.CREATE, criado);
            return ResponseEntity.status(HttpStatus.CREATED).body(criado);
        } catch (IllegalArgumentException e) {
            return recusa(e);
        }
    }

    /**
     * Edita o gatilho. Com {@code If-Match} (o {@code updatedAt} que a tela leu), só grava se ele continua
     * nessa versão — senão 409 {@code TRIGGER_CONFLICT} com o {@code updatedAt} atual, e nada muda. Sem o
     * cabeçalho a gravação é a de sempre. Um {@code If-Match} que não é uma versão (p.ex. {@code "bogus"}) não
     * casa com nenhuma: 409, e não a gravação incondicional que ele passava antes.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ScheduledTriggerDto dto,
                                    @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        ScheduledTriggerDto existente;
        try {
            existente = alcancavel(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
        Instant esperado;
        try {
            esperado = versaoEsperada(ifMatch);
        } catch (DateTimeParseException e) {
            return conflito(new TriggerConflictException(id, existente.updatedAt()));
        }
        try {
            ScheduledTriggerDto gravado =
                    delegate.update(id, doTenantDaIdentidade(comTenant(dto, existente)), esperado);
            auditar(AuditAction.UPDATE, gravado);
            return ResponseEntity.ok(gravado);
        } catch (TriggerConflictException e) {
            return conflito(e);
        } catch (IllegalArgumentException e) {
            return recusa(e);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            ScheduledTriggerDto excluido = alcancavel(id);
            delegate.delete(id);
            auditar(AuditAction.DELETE, excluido);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/fire")
    public ResponseEntity<?> fireNow(@PathVariable String id) {
        try {
            alcancavel(id);
            ScheduledTriggerDto disparado = delegate.fireNow(id);
            auditar(AuditAction.AGENT_EXECUTE, disparado);
            return ResponseEntity.ok(disparado);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Corpo que não desserializa — um payload que não é objeto JSON ({@code [1,2]}, {@code "texto"}), por
     * exemplo. Sem isto a resposta era um 400 sem mensagem, e a tela dizia só "Bad Request" (RCHFLW-191).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> corpoInvalido(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Malformed trigger body: payload must be a JSON object and every field must have its type",
                "code", "TRIGGER_BODY_INVALID"));
    }

    /** 400 com o {@code code} da recusa, quando ela tem um — é ele que a tela traduz. */
    private static ResponseEntity<?> recusa(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        if (e instanceof TriggerRejectedException rejeitado) {
            body.put("code", rejeitado.code());
        }
        return ResponseEntity.badRequest().body(body);
    }

    private static ResponseEntity<?> conflito(TriggerConflictException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getMessage());
        body.put("code", TriggerConflictException.CODE);
        body.put("updatedAt", e.currentUpdatedAt());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * O {@code updatedAt} pedido no {@code If-Match}, sem as aspas da entity tag (e sem o {@code W/}); nulo
     * quando não há pré-condição — cabeçalho ausente, vazio ou {@code *}.
     *
     * @throws DateTimeParseException quando o valor não é um instante — não casa com versão nenhuma
     */
    static Instant versaoEsperada(String ifMatch) {
        if (ifMatch == null) return null;
        String valor = ifMatch.trim();
        if (valor.startsWith("W/")) valor = valor.substring(2).trim();
        if (valor.length() >= 2 && valor.startsWith("\"") && valor.endsWith("\"")) {
            valor = valor.substring(1, valor.length() - 1);
        }
        return valor.isEmpty() || "*".equals(valor) ? null : Instant.parse(valor);
    }

    /** Uma entrada na trilha de auditoria (no-op sem repositório; nunca lança). */
    private void auditar(AuditAction acao, ScheduledTriggerDto gatilho) {
        Map<String, String> detalhes = new LinkedHashMap<>();
        detalhes.put("name", gatilho.name());
        detalhes.put("agentId", gatilho.agentId());
        detalhes.put("triggerTenant", gatilho.tenantId());
        if (acao == AuditAction.AGENT_EXECUTE && gatilho.lastRequestId() != null) {
            detalhes.put("requestId", gatilho.lastRequestId());
        } else if (acao != AuditAction.DELETE) {
            detalhes.put("cronExpression", gatilho.cronExpression());
            detalhes.put("enabled", String.valueOf(gatilho.enabled()));
        }
        auditTrail.record(acao, AUDIT_RESOURCE, gatilho.id(), true, null, detalhes);
    }

    /**
     * O gatilho, se quem pede pode agir no tenant dele. O de outro tenant responde como inexistente,
     * com a mesma mensagem do id que não existe — um 403 aqui diria a quem não pode que o id é válido.
     */
    private ScheduledTriggerDto alcancavel(String id) {
        ScheduledTriggerDto trigger = delegate.get(id);
        if (!tenantScope.mayActOn(trigger.tenantId())) {
            throw new IllegalArgumentException("Trigger not found: " + id);
        }
        return trigger;
    }

    /** {@code true} quando o registro já tem um gatilho com esse id, de qualquer tenant. */
    private boolean existe(String id) {
        try {
            delegate.get(id);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * O corpo de uma edição com o tenant do gatilho existente quando o dele vem em branco: branco quer
     * dizer "não mexi no tenant", não "mova para o meu". Um tenant explícito segue para a checagem de
     * {@link #doTenantDaIdentidade}.
     */
    private static ScheduledTriggerDto comTenant(ScheduledTriggerDto dto, ScheduledTriggerDto existente) {
        if (dto.tenantId() != null && !dto.tenantId().isBlank()) {
            return dto;
        }
        return comTenant(dto, existente.tenantId());
    }

    /** O corpo com o tenant que a identidade permite ({@link TenantScope#forAction}). */
    private ScheduledTriggerDto doTenantDaIdentidade(ScheduledTriggerDto dto) {
        String tenant = tenantScope.forAction(dto.tenantId());
        if (tenant == null || tenant.equals(dto.tenantId())) {
            return dto;
        }
        return comTenant(dto, tenant);
    }

    private static ScheduledTriggerDto comTenant(ScheduledTriggerDto dto, String tenant) {
        return new ScheduledTriggerDto(dto.id(), dto.name(), dto.cronExpression(), tenant, dto.agentId(),
                dto.payload(), dto.enabled(), dto.createdAt(), dto.lastFiredAt(), dto.lastError(),
                dto.lastRequestId());
    }
}
