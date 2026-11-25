package br.com.archflow.plugins.tools;

import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** {@code String.valueOf(mapa)} transformava "{message=...}" em vez do texto do usuário. */
@DisplayName("TextTransformTool transforma o texto do mapa")
class MapInputTest {

    @Test
    void transformaOCampoDeTextoDoMapa() throws Exception {
        TextTransformTool tool = new TextTransformTool();
        tool.initialize(Map.of());
        ExecutionContext context = mock(ExecutionContext.class);

        assertThat(tool.execute("uppercase", Map.of("message", "olá mundo", "sessionId", "s"), context))
                .isEqualTo("OLÁ MUNDO");
        assertThat(tool.execute("wordcount", Map.of("text", "um dois três"), context)).isEqualTo(3);
    }
}
