package br.com.archflow.api.skills.store;

import java.util.Set;

/**
 * Quais skills cada tenant deixou ativas (RCHFLW-66).
 *
 * <p>O conteúdo das skills vem do diretório {@code archflow.skills.directory} e é o mesmo para a
 * instância inteira ({@code SkillsManager}); o que é de cada tenant é só a escolha de quais ficam
 * ligadas. Antes essa escolha vivia num mapa do controller e no set global do {@code SkillsManager}
 * — o segundo misturava os tenants, e os dois se perdiam no restart.
 *
 * <p>Em memória por default (dev), durável em {@code JdbcPersistenceConfiguration} (migration V6_14),
 * no mesmo par dos outros stores. O tenant é sempre o da requisição ({@code TenantContext}), passado por
 * quem chama — nunca um tenant vindo do corpo.
 */
public interface SkillActivationStore {

    /** Nomes das skills ativas do tenant; vazio quando ele nunca ativou nenhuma. */
    Set<String> activeSkills(String tenantId);

    /** Marca a skill como ativa para o tenant. Idempotente. */
    void activate(String tenantId, String skillName);

    /** Marca a skill como inativa para o tenant. Idempotente. */
    void deactivate(String tenantId, String skillName);
}
