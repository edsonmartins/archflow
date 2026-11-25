package br.com.archflow.api.web.triggers;

import br.com.archflow.api.config.TenantScope;
import br.com.archflow.api.triggers.ScheduledTriggerController;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
@RequestMapping("/api/admin/triggers")
public class SpringScheduledTriggerController {

    private final ScheduledTriggerController delegate;
    private final TenantScope tenantScope;

    public SpringScheduledTriggerController(ScheduledTriggerController delegate, TenantScope tenantScope) {
        this.delegate = delegate;
        this.tenantScope = tenantScope;
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
            return ResponseEntity.status(HttpStatus.CREATED).body(delegate.create(doTenant));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody ScheduledTriggerDto dto) {
        ScheduledTriggerDto existente;
        try {
            existente = alcancavel(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        }
        try {
            return ResponseEntity.ok(delegate.update(id, doTenantDaIdentidade(comTenant(dto, existente))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        try {
            alcancavel(id);
            delegate.delete(id);
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
            return ResponseEntity.ok(delegate.fireNow(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", e.getMessage()));
        }
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
