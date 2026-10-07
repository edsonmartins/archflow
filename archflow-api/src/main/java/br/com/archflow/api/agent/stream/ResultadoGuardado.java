package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.vendax.VendaxResult;

import java.util.Optional;

/**
 * Onde o resultado de uma chave terminada fica guardado, para uma repetição da mesma chave receber
 * o {@code fim} de novo sem chamar o modelo.
 *
 * <p>Só se guarda resultado {@code OK}: repetir uma chave depois de uma falha ou de um cancelamento
 * é pedir para tentar de novo, e devolver o erro guardado impediria isso.</p>
 */
public interface ResultadoGuardado {

    /** O resultado guardado para a chave, se ainda não expirou. */
    Optional<VendaxResult> buscar(String chave);

    void guardar(String chave, VendaxResult resultado);
}
