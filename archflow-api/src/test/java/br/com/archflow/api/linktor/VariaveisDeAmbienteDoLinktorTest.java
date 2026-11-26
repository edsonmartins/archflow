package br.com.archflow.api.linktor;

import br.com.archflow.api.config.ArchflowBeanConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-114: a tela Administração › Linktor e o {@code readme.md} (Production Checklist) mandam o operador
 * tornar a configuração permanente com as variáveis {@code ARCHFLOW_LINKTOR_*}, porque o que se salva pela tela fica
 * em memória e some no restart. Os nomes não podem ser chute: as propriedades são camelCase
 * ({@code archflow.linktor.apiBaseUrl}), e o nome "natural" {@code ARCHFLOW_LINKTOR_API_BASE_URL} não é o
 * que o Spring procura.
 *
 * <p>O teste lê os placeholders do próprio {@code @Value} do bean (não uma cópia deles) e resolve cada um
 * num ambiente montado como o do Spring Boot, com a variável documentada no lugar das do sistema.</p>
 */
@DisplayName("Linktor — variáveis de ambiente que tornam a configuração permanente")
class VariaveisDeAmbienteDoLinktorTest {

    /** Os nomes que a tela e a documentação mostram, na mesma ordem dos parâmetros do bean. */
    private static final List<String> VARIAVEIS_DOCUMENTADAS = List.of(
            "ARCHFLOW_LINKTOR_ENABLED",
            "ARCHFLOW_LINKTOR_APIBASEURL",
            "ARCHFLOW_LINKTOR_APIKEY",
            "ARCHFLOW_LINKTOR_ACCESSTOKEN",
            "ARCHFLOW_LINKTOR_MCPCOMMAND",
            "ARCHFLOW_LINKTOR_TIMEOUTSECONDS");

    private static List<String> placeholdersDoBean() {
        Method bean = Arrays.stream(ArchflowBeanConfiguration.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("linktorConfigController"))
                .findFirst()
                .orElseThrow();
        List<String> placeholders = new ArrayList<>();
        for (Parameter p : bean.getParameters()) {
            placeholders.add(p.getAnnotation(Value.class).value());
        }
        return placeholders;
    }

    private static StandardEnvironment ambienteComVariaveis(Map<String, Object> variaveis) {
        StandardEnvironment env = new StandardEnvironment();
        MutablePropertySources fontes = env.getPropertySources();
        fontes.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variaveis));
        // A propriedade de sistema venceria a variável e mascararia o caso; e o Boot liga esta fonte
        // na frente de todas, então o teste também liga.
        fontes.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        ConfigurationPropertySources.attach(env);
        return env;
    }

    @Test
    @DisplayName("cada variável documentada chega ao @Value do bean")
    void cadaVariavelDocumentadaResolveOPlaceholderDoBean() {
        List<String> placeholders = placeholdersDoBean();
        assertThat(placeholders).hasSameSizeAs(VARIAVEIS_DOCUMENTADAS);

        for (int i = 0; i < placeholders.size(); i++) {
            String variavel = VARIAVEIS_DOCUMENTADAS.get(i);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put(variavel, "valor-da-" + variavel);
            StandardEnvironment env = ambienteComVariaveis(vars);

            assertThat(env.resolvePlaceholders(placeholders.get(i)))
                    .as("%s deveria resolver %s", variavel, placeholders.get(i))
                    .isEqualTo("valor-da-" + variavel);
        }
    }

    @Test
    @DisplayName("separar as palavras com _ não funciona — por isso a documentação avisa")
    void nomeComPalavrasSeparadasNaoResolve() {
        String apiBaseUrl = placeholdersDoBean().get(1);
        StandardEnvironment env = ambienteComVariaveis(
                Map.of("ARCHFLOW_LINKTOR_API_BASE_URL", "http://linktor:8081/api/v1"));

        assertThat(env.resolvePlaceholders(apiBaseUrl)).isEqualTo("http://localhost:8081/api/v1");
    }
}
