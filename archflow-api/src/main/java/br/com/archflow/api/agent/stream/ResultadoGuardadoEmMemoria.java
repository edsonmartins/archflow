package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.vendax.VendaxResult;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Guarda em memória, com validade. <b>Por réplica</b>: uma repetição que caia noutra réplica não
 * acha o resultado e executa de novo. Uma versão em banco é o passo seguinte.
 */
public final class ResultadoGuardadoEmMemoria implements ResultadoGuardado {

    /** Validade padrão. */
    public static final Duration PADRAO = Duration.ofMinutes(10);

    private record Entrada(VendaxResult resultado, long expiraEmNanos) {
    }

    private final Map<String, Entrada> entradas = new ConcurrentHashMap<>();
    private final long validadeNanos;
    private final LongSupplier relogio;

    public ResultadoGuardadoEmMemoria(Duration validade) {
        this(validade, System::nanoTime);
    }

    ResultadoGuardadoEmMemoria(Duration validade, LongSupplier relogio) {
        if (validade == null || validade.isNegative()) {
            throw new IllegalArgumentException("validade inválida: " + validade);
        }
        this.validadeNanos = validade.toNanos();
        this.relogio = relogio;
    }

    @Override
    public Optional<VendaxResult> buscar(String chave) {
        Entrada e = chave == null ? null : entradas.get(chave);
        if (e == null) {
            return Optional.empty();
        }
        if (relogio.getAsLong() - e.expiraEmNanos >= 0) {
            entradas.remove(chave, e);
            return Optional.empty();
        }
        return Optional.of(e.resultado);
    }

    @Override
    public void guardar(String chave, VendaxResult resultado) {
        if (chave == null || resultado == null || validadeNanos == 0) {
            return;
        }
        long agora = relogio.getAsLong();
        // Varredura barata a cada escrita: o mapa não cresce sem limite num processo longo.
        entradas.values().removeIf(e -> agora - e.expiraEmNanos >= 0);
        entradas.put(chave, new Entrada(resultado, agora + validadeNanos));
    }
}
