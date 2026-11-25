package br.com.archflow.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * As migrações são numeradas por módulo (V1_x core … V6_x api), então uma migração nova de prefixo
 * baixo sempre chega "fora de ordem" num banco já migrado. O {@code out-of-order} morava só no
 * {@code application-prod.yml}: um banco dev existente (o {@code docker compose} sobe com
 * {@code SPRING_PROFILES_ACTIVE=dev}) parava de subir com "Detected resolved migration not applied
 * to database: 1.3.1" assim que chegou a V1_3_1. A chave fica na configuração base, que todo perfil
 * herda — e nenhum perfil pode desligá-la por engano.
 */
class FlywayForaDeOrdemEmTodoPerfilTest {

    private static Properties yaml(String arquivo) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(arquivo));
        return factory.getObject();
    }

    @Test
    @DisplayName("out-of-order está na configuração base, que dev e prod herdam")
    void naConfiguracaoBase() {
        assertThat(yaml("application.yml").getProperty("spring.flyway.out-of-order"))
                .isEqualTo("${SPRING_FLYWAY_OUT_OF_ORDER:true}");
    }

    @Test
    @DisplayName("nenhum perfil sobrescreve a chave")
    void nenhumPerfilSobrescreve() {
        assertThat(yaml("application-dev.yml").getProperty("spring.flyway.out-of-order")).isNull();
        assertThat(yaml("application-prod.yml").getProperty("spring.flyway.out-of-order")).isNull();
    }
}
