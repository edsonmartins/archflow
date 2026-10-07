package br.com.archflow.decision;

import java.util.Optional;

/**
 * Como o hospedeiro entrega a chave de um provedor, por tenant. A chave <b>nunca</b> vem do
 * documento do fluxo: governança não pode ser contornada por um campo esquecido num nó.
 */
@FunctionalInterface
public interface DecisionKeys {

    Optional<String> resolve(String tenantId, String keyRef);

    DecisionKeys NONE = (tenantId, keyRef) -> Optional.empty();
}
