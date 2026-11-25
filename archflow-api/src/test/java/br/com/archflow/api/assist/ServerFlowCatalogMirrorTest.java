package br.com.archflow.api.assist;

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
 * Os tipos da paleta que a assistência descreve ({@link ServerFlowCatalog#DESIGNER_NODE_TYPES}) são os do
 * designer ({@code NODE_TYPE_TO_CATEGORY} em {@code constants.ts}), sem as anotações. Nas duas direções:
 * um tipo novo no designer esquecido aqui some da lista "sem execução" que o explain-error mostra; um tipo
 * tirado do designer e deixado aqui seria oferecido ao modelo como nó que não existe.
 *
 * <p>Lê o arquivo do designer do mesmo repositório, como o {@code CanvasAnnotationMirrorTest}.</p>
 */
@DisplayName("assistência: tipos da paleta espelham o designer")
class ServerFlowCatalogMirrorTest {

    private static final Path CONSTANTS =
            Path.of("..", "archflow-ui", "src", "components", "FlowCanvas", "constants.ts");

    @Test
    @DisplayName("DESIGNER_NODE_TYPES = tipos de NODE_TYPE_TO_CATEGORY menos as anotações")
    void mesmaLista() throws Exception {
        assertThat(CONSTANTS).as("constants.ts do designer").exists();
        String fonte = Files.readString(CONSTANTS);
        int inicio = fonte.indexOf("NODE_TYPE_TO_CATEGORY");
        String mapa = fonte.substring(inicio, fonte.indexOf('}', fonte.indexOf('{', inicio)));
        Matcher m = Pattern.compile("'([a-z0-9-]+)'\\s*:\\s*'([a-z]+)'").matcher(mapa);
        Set<String> doDesigner = m.results()
                .filter(r -> !"annotation".equals(r.group(2)))
                .map(r -> r.group(1))
                .collect(Collectors.toSet());

        assertThat(doDesigner).as("tipos no designer").isNotEmpty();
        assertThat(doDesigner).doesNotContainAnyElementsOf(CanvasAnnotation.TYPES);
        assertThat(Set.copyOf(ServerFlowCatalog.DESIGNER_NODE_TYPES)).isEqualTo(doDesigner);
    }
}
