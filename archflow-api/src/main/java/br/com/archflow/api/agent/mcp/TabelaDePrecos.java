package br.com.archflow.api.agent.mcp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Preço declarado por modelo, para transformar tokens em custo.
 *
 * <h2>Por que é declarado, e não descoberto</h2>
 *
 * <p>O langchain4j devolve contagem de tokens, não custo. Alguns provedores informam o custo na
 * resposta, mas o campo não atravessa a abstração; e preço embutido no código envelhece em
 * silêncio a cada reajuste. Quem opera o runtime é quem sabe quanto paga — e declara.</p>
 *
 * <h2>Sem preço, sem custo</h2>
 *
 * <p>Modelo que não está na tabela produz custo {@code null}, não zero. Zero diria ao teto do
 * tenant que a execução foi de graça; nulo diz que ninguém declarou quanto ela custa, e é isso que
 * aparece para quem for conferir.</p>
 *
 * <h2>Configuração</h2>
 *
 * <p>Em <b>centavos de real por milhão de tokens</b> — a moeda em que o VendaX soma o teto mensal
 * do tenant. A chave é o {@code provider/modelo} da configuração resolvida; como ela tem barras e
 * pontos, vai entre colchetes:</p>
 *
 * <pre>
 * archflow.llm.precos[openrouter/google/gemini-2.5-flash-lite].entrada=10
 * archflow.llm.precos[openrouter/google/gemini-2.5-flash-lite].saida=40
 * </pre>
 *
 * <p>Nome de variável de ambiente não carrega barra nem ponto, então há também a forma em texto,
 * para {@code ARCHFLOW_LLM_PRECOS} (ver {@link #doTexto}):</p>
 *
 * <pre>
 * openrouter/google/gemini-2.5-flash-lite=10,40;anthropic/claude-sonnet-5=1650,8250
 * </pre>
 */
public final class TabelaDePrecos {

    private static final BigDecimal MILHAO = BigDecimal.valueOf(1_000_000);

    /**
     * @param entradaCentavosPorMilhao preço de um milhão de tokens de entrada
     * @param saidaCentavosPorMilhao   preço de um milhão de tokens de saída
     */
    public record Preco(BigDecimal entradaCentavosPorMilhao, BigDecimal saidaCentavosPorMilhao) {
        public Preco {
            Objects.requireNonNull(entradaCentavosPorMilhao, "preço de entrada");
            Objects.requireNonNull(saidaCentavosPorMilhao, "preço de saída");
            if (entradaCentavosPorMilhao.signum() < 0 || saidaCentavosPorMilhao.signum() < 0) {
                throw new IllegalArgumentException("preço negativo");
            }
        }
    }

    private final Map<String, Preco> precos;

    public TabelaDePrecos(Map<String, Preco> precos) {
        Map<String, Preco> normalizado = new HashMap<>();
        if (precos != null) {
            precos.forEach((modelo, preco) -> {
                if (modelo != null && !modelo.isBlank() && preco != null) {
                    normalizado.put(chave(modelo), preco);
                }
            });
        }
        this.precos = Map.copyOf(normalizado);
    }

    public static TabelaDePrecos vazia() {
        return new TabelaDePrecos(Map.of());
    }

    /**
     * Lê a forma da configuração: {@code modelo → {entrada, saida}}.
     *
     * <p>Um modelo com um dos dois preços ausente ou ilegível fica <b>fora</b> da tabela, em vez de
     * entrar com zero no lado que faltou — um preço pela metade subestima em silêncio.</p>
     */
    public static TabelaDePrecos daConfiguracao(Map<String, Map<String, String>> config) {
        Map<String, Preco> precos = new HashMap<>();
        if (config != null) {
            config.forEach((modelo, valores) -> {
                if (valores == null) {
                    return;
                }
                BigDecimal entrada = decimal(valores.get("entrada"));
                BigDecimal saida = decimal(valores.get("saida"));
                if (entrada != null && saida != null
                        && entrada.signum() >= 0 && saida.signum() >= 0) {
                    precos.put(modelo, new Preco(entrada, saida));
                }
            });
        }
        return new TabelaDePrecos(precos);
    }

    /**
     * A forma em texto: {@code modelo=entrada,saida}, separados por {@code ;}.
     *
     * <p>{@code =} e {@code ;} não aparecem em id de modelo (que pode ter {@code /}, {@code .},
     * {@code -} e {@code :}). Entrada malformada fica fora, como na outra forma — e é
     * <b>informada</b>, porque aqui um erro de digitação apagaria o preço em silêncio.</p>
     *
     * @return os pares lidos; {@link #ignorados} diz o que ficou de fora
     */
    public static Leitura doTexto(String texto) {
        Map<String, Map<String, String>> config = new HashMap<>();
        java.util.List<String> ignorados = new java.util.ArrayList<>();
        if (texto != null) {
            for (String item : texto.split(";")) {
                String par = item.trim();
                if (par.isEmpty()) {
                    continue;
                }
                int igual = par.lastIndexOf('=');
                String[] valores = igual < 0 ? new String[0] : par.substring(igual + 1).split(",");
                if (igual <= 0 || valores.length != 2) {
                    ignorados.add(par);
                    continue;
                }
                config.put(par.substring(0, igual).trim(),
                        Map.of("entrada", valores[0].trim(), "saida", valores[1].trim()));
            }
        }
        TabelaDePrecos tabela = daConfiguracao(config);
        config.keySet().stream()
                .filter(modelo -> !tabela.precos.containsKey(chave(modelo)))
                .forEach(ignorados::add);
        return new Leitura(tabela, java.util.List.copyOf(ignorados));
    }

    /** Resultado da forma em texto: a tabela e as entradas que não entraram nela. */
    public record Leitura(TabelaDePrecos tabela, java.util.List<String> ignorados) {
    }

    /** As duas tabelas juntas; em conflito, vale {@code outra}. */
    public TabelaDePrecos com(TabelaDePrecos outra) {
        Map<String, Preco> juntos = new HashMap<>(precos);
        if (outra != null) {
            juntos.putAll(outra.precos);
        }
        return new TabelaDePrecos(juntos);
    }

    /** Modelos com preço declarado. */
    public java.util.Set<String> modelos() {
        return precos.keySet();
    }

    /**
     * Custo do consumo, em centavos, <b>arredondado para cima</b>.
     *
     * <p>Para cima porque o número alimenta um teto: uma consulta de 812 tokens num modelo barato
     * custa uma fração de centavo, e arredondar para baixo faria mil delas custarem zero.</p>
     *
     * @return {@code null} quando não há como saber: sem tokens informados, sem preço declarado,
     *         ou mais de um modelo no mesmo consumo (a divisão de tokens entre eles se perdeu)
     */
    public Long custoEmCentavos(ContadorDeUso.Resumo uso) {
        if (uso == null || uso.modelo() == null || uso.modelo().contains(",")
                || uso.tokens() == null) {
            return null;
        }
        Preco preco = precos.get(chave(uso.modelo()));
        if (preco == null) {
            return null;
        }
        BigDecimal entrada = BigDecimal.valueOf(uso.tokensEntrada() == null ? 0 : uso.tokensEntrada())
                .multiply(preco.entradaCentavosPorMilhao());
        BigDecimal saida = BigDecimal.valueOf(uso.tokensSaida() == null ? 0 : uso.tokensSaida())
                .multiply(preco.saidaCentavosPorMilhao());
        return entrada.add(saida).divide(MILHAO, 0, RoundingMode.CEILING).longValueExact();
    }

    private static String chave(String modelo) {
        return modelo.trim().toLowerCase(Locale.ROOT);
    }

    private static BigDecimal decimal(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(valor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
