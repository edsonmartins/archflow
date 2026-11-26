package br.com.archflow.api.usage;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A soma de vários {@link LancamentoDeUso} — uma execução retomada, um tenant num mês.
 *
 * <p>Custo desconhecido não vira zero. {@code custoCentavos}/{@code custoReais} somam só os
 * lançamentos que têm preço e são {@code null} quando nenhum tem; {@code execucoesSemPreco} diz
 * quantos ficaram de fora, para a tela não apresentar uma soma parcial como se fosse o total.
 *
 * @param execucoes          lançamentos somados (execuções que chamaram modelo)
 * @param tokensEntrada      soma dos tokens de entrada informados
 * @param tokensSaida        soma dos tokens de saída informados
 * @param custoCentavos      soma dos custos com preço, em centavos; {@code null} se nenhum tinha preço
 * @param custoReais         a mesma soma em reais, sem arredondar; {@code null} se nenhum tinha preço
 * @param custoDeclaradoUsd  soma do custo declarado pelo provedor; {@code null} se nenhum declarou
 * @param execucoesSemPreco  lançamentos sem custo pela tabela de preços
 * @param modelos            os {@code provedor/modelo} que os lançamentos registraram, sem repetir, na
 *                           ordem em que apareceram; vazia quando nenhum disse o modelo. O lançamento já
 *                           o gravava, e a soma o descartava — a execução mostrava tokens e "sem preço"
 *                           sem dizer de qual modelo, que é justamente o que falta para pôr o preço
 *                           (RCHFLW-190)
 */
public record UsoSomado(int execucoes, long tokensEntrada, long tokensSaida, Long custoCentavos,
                        Double custoReais, Double custoDeclaradoUsd, int execucoesSemPreco,
                        List<String> modelos) {

    /** Nada medido. */
    public static final UsoSomado VAZIO = new UsoSomado(0, 0, 0, null, null, null, 0, List.of());

    public UsoSomado {
        modelos = modelos == null ? List.of() : List.copyOf(modelos);
    }

    /** Sem a lista de modelos — a forma que existia antes dela. */
    public UsoSomado(int execucoes, long tokensEntrada, long tokensSaida, Long custoCentavos,
                     Double custoReais, Double custoDeclaradoUsd, int execucoesSemPreco) {
        this(execucoes, tokensEntrada, tokensSaida, custoCentavos, custoReais, custoDeclaradoUsd,
                execucoesSemPreco, List.of());
    }

    /** Total de tokens. */
    public long tokens() {
        return tokensEntrada + tokensSaida;
    }

    /** Soma os lançamentos; vazio dá {@link #VAZIO}. */
    public static UsoSomado de(Collection<LancamentoDeUso> lancamentos) {
        int execucoes = 0;
        int semPreco = 0;
        long entrada = 0;
        long saida = 0;
        Long centavos = null;
        Double reais = null;
        Double declarado = null;
        Set<String> modelos = new LinkedHashSet<>();
        for (LancamentoDeUso l : lancamentos) {
            execucoes++;
            entrada += l.tokensEntrada() == null ? 0 : l.tokensEntrada();
            saida += l.tokensSaida() == null ? 0 : l.tokensSaida();
            if (l.custoCentavos() == null) {
                semPreco++;
            } else {
                centavos = (centavos == null ? 0 : centavos) + l.custoCentavos();
                reais = (reais == null ? 0.0 : reais) + (l.custoReais() == null ? 0.0 : l.custoReais());
            }
            if (l.custoDeclaradoUsd() != null) {
                declarado = (declarado == null ? 0.0 : declarado) + l.custoDeclaradoUsd();
            }
            // Um lançamento com mais de um modelo os grava separados por vírgula (LancamentoDeUso).
            if (l.modelo() != null) {
                for (String m : l.modelo().split(",")) {
                    if (!m.isBlank()) {
                        modelos.add(m.strip());
                    }
                }
            }
        }
        return new UsoSomado(execucoes, entrada, saida, centavos, reais, declarado, semPreco,
                List.copyOf(modelos));
    }
}
