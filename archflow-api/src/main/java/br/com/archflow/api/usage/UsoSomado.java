package br.com.archflow.api.usage;

import java.util.Collection;

/**
 * A soma de vários {@link LancamentoDeUso} — uma execução retomada, um tenant num mês.
 *
 * <p>Custo desconhecido não vira zero. {@code custoCentavos}/{@code custoReais} somam só os
 * lançamentos que têm preço e são {@code null} quando nenhum tem; {@code execucoesSemPreco} diz
 * quantos ficaram de fora, para a tela não apresentar uma soma parcial como se fosse o total.
 *
 * <p>Execuções contam por {@code execucaoDeFluxoId} distinto, não por lançamento: uma execução que
 * chama o modelo antes e depois de uma aprovação tem um lançamento por travessia
 * ({@code RegistroDeUsoListener} abre um contador novo na retomada), e a cobrança contava duas
 * execuções e duas "sem preço" para uma só (RCHFLW-195 item 3). Tokens e custos continuam somando
 * todos os lançamentos. Só o que o admin mostra usa esta soma — o uso que vai ao Core do VendaX vem do
 * {@code ContadorDeUso} da execução e não passa por aqui.
 *
 * @param execucoes          execuções distintas que chamaram modelo (lançamento sem id da execução
 *                           conta sozinho)
 * @param tokensEntrada      soma dos tokens de entrada informados
 * @param tokensSaida        soma dos tokens de saída informados
 * @param custoCentavos      soma dos custos com preço, em centavos; {@code null} se nenhum tinha preço
 * @param custoReais         a mesma soma em reais, sem arredondar; {@code null} se nenhum tinha preço
 * @param custoDeclaradoUsd  soma do custo declarado pelo provedor; {@code null} se nenhum declarou
 * @param execucoesSemPreco  execuções distintas com algum lançamento sem custo pela tabela de preços
 */
public record UsoSomado(int execucoes, long tokensEntrada, long tokensSaida, Long custoCentavos,
                        Double custoReais, Double custoDeclaradoUsd, int execucoesSemPreco) {

    /** Nada medido. */
    public static final UsoSomado VAZIO = new UsoSomado(0, 0, 0, null, null, null, 0);

    /** Total de tokens. */
    public long tokens() {
        return tokensEntrada + tokensSaida;
    }

    /** Soma os lançamentos; vazio dá {@link #VAZIO}. */
    public static UsoSomado de(Collection<LancamentoDeUso> lancamentos) {
        java.util.Set<Object> execucoes = new java.util.HashSet<>();
        java.util.Set<Object> semPreco = new java.util.HashSet<>();
        long entrada = 0;
        long saida = 0;
        Long centavos = null;
        Double reais = null;
        Double declarado = null;
        for (LancamentoDeUso l : lancamentos) {
            // Sem o id da execução, o próprio lançamento é a unidade.
            Object execucao = l.execucaoDeFluxoId() != null && !l.execucaoDeFluxoId().isBlank()
                    ? l.execucaoDeFluxoId()
                    : l;
            execucoes.add(execucao);
            entrada += l.tokensEntrada() == null ? 0 : l.tokensEntrada();
            saida += l.tokensSaida() == null ? 0 : l.tokensSaida();
            if (l.custoCentavos() == null) {
                semPreco.add(execucao);
            } else {
                centavos = (centavos == null ? 0 : centavos) + l.custoCentavos();
                reais = (reais == null ? 0.0 : reais) + (l.custoReais() == null ? 0.0 : l.custoReais());
            }
            if (l.custoDeclaradoUsd() != null) {
                declarado = (declarado == null ? 0.0 : declarado) + l.custoDeclaradoUsd();
            }
        }
        return new UsoSomado(execucoes.size(), entrada, saida, centavos, reais, declarado, semPreco.size());
    }
}
