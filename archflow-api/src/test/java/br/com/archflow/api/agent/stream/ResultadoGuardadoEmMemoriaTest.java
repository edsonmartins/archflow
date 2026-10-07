package br.com.archflow.api.agent.stream;

import br.com.archflow.api.agent.vendax.VendaxInvoke;
import br.com.archflow.api.agent.vendax.VendaxResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("guarda em memória do resultado por chave")
class ResultadoGuardadoEmMemoriaTest {

    private static VendaxResult resultado() {
        return VendaxResult.ok(new VendaxInvoke("1.0", "t1", "c1", "a", "m", "x", null, null, null,
                null, null, null, null), "text", "oi");
    }

    @Test
    @DisplayName("devolve dentro da validade e esquece depois dela")
    void validade() {
        AtomicLong agora = new AtomicLong();
        var guarda = new ResultadoGuardadoEmMemoria(Duration.ofMinutes(10), agora::get);

        guarda.guardar("k", resultado());
        assertThat(guarda.buscar("k")).isPresent();

        agora.addAndGet(Duration.ofMinutes(9).toNanos());
        assertThat(guarda.buscar("k")).isPresent();

        agora.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(guarda.buscar("k")).isEmpty();
    }

    @Test
    @DisplayName("validade zero desliga a guarda")
    void zeroDesliga() {
        var guarda = new ResultadoGuardadoEmMemoria(Duration.ZERO);
        guarda.guardar("k", resultado());
        assertThat(guarda.buscar("k")).isEmpty();
    }

    @Test
    @DisplayName("a conexão que fecha depois do fim não cancela uma nova execução da mesma chave")
    void cancelamentoPorIdentidade() {
        var registro = new ExecucoesEmAndamento();
        var antiga = registro.registrar("k", "t1", null).orElseThrow();
        registro.concluir("k");
        var nova = registro.registrar("k", "t1", null).orElseThrow();

        assertThat(registro.cancelar(antiga)).isFalse();
        assertThat(nova.cancelamento()).isNotDone();
        assertThat(registro.cancelar(nova)).isTrue();
    }
}
