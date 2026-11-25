package br.com.archflow.api.flow;

import br.com.archflow.model.flow.CanvasAnnotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lista de anotações do designer (categoria {@code annotation} em {@code constants.ts}) é a mesma que o
 * motor ignora ({@link CanvasAnnotation#TYPES}). Nas duas direções: uma anotação nova esquecida no motor
 * volta a derrubar o fluxo (RCHFLW-12); um tipo tirado do designer e deixado no motor faria o motor
 * pular um nó que alguém passou a usar como passo de verdade.
 *
 * <p>Vive no archflow-api, como o {@code AdapterNodeTypesMirrorTest}: lê o arquivo do designer do mesmo
 * repositório.</p>
 */
@DisplayName("anotações: designer espelha CanvasAnnotation")
class CanvasAnnotationMirrorTest {

    private static final Path CONSTANTS =
            Path.of("..", "archflow-ui", "src", "components", "FlowCanvas", "constants.ts");

    @Test
    @DisplayName("os tipos com categoria 'annotation' no designer são exatamente os que o motor ignora")
    void mesmaLista() throws Exception {
        assertThat(CONSTANTS).as("constants.ts do designer").exists();
        Matcher m = Pattern.compile("'([a-z0-9-]+)'\\s*:\\s*'annotation'").matcher(Files.readString(CONSTANTS));
        Set<String> doDesigner = m.results().map(r -> r.group(1)).collect(Collectors.toSet());

        assertThat(doDesigner).as("anotações no designer").isNotEmpty();
        assertThat(doDesigner).isEqualTo(CanvasAnnotation.TYPES);
    }
}
