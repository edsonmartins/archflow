package br.com.archflow.api.jobs;

import br.com.archflow.api.config.ArchflowBeanConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-58: no perfil dev o worker da fila de jobs sobe — sem ele a ingestão do Knowledge ficava QUEUED para
 * sempre, porque o bean {@code JobWorkerLoop} só existe com {@code archflow.jobs.worker.enabled=true} e só o
 * {@code application-prod.yml} a ligava.
 *
 * <p>Confere contra a condição do próprio bean (lida do {@code @ConditionalOnProperty}), não contra o nome da
 * propriedade escrito aqui: se o bean passar a exigir outra chave, o teste acompanha.</p>
 */
@DisplayName("worker de jobs liga nos perfis dev e prod")
class JobWorkerProfileTest {

    @ParameterizedTest(name = "application-{0}.yml")
    @ValueSource(strings = {"dev", "prod"})
    void perfilLigaOWorker(String profile) throws Exception {
        Method bean = Arrays.stream(ArchflowBeanConfiguration.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("jobWorkerLoop"))
                .findFirst().orElseThrow();
        ConditionalOnProperty condition = bean.getAnnotation(ConditionalOnProperty.class);
        assertThat(condition).as("o bean do worker é condicional").isNotNull();
        String property = condition.name()[0];

        StandardEnvironment env = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load(profile, new ClassPathResource("application-" + profile + ".yml"))) {
            env.getPropertySources().addLast(source);
        }

        assertThat(env.getProperty(property))
                .as("%s no perfil %s (sem a variável de ambiente)", property, profile)
                .isEqualTo(condition.havingValue());
    }
}
