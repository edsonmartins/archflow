package br.com.archflow.api.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;

/**
 * Definição única de "ambiente de desenvolvimento" para o archflow.
 *
 * <p>O conceito de dev-like governa decisões de segurança (senha admin fixa
 * em {@code ArchflowBeanConfiguration} vs. aleatória; o
 * {@link ProductionReadinessGuard} permitir ou não stores em memória), então
 * precisa ter uma só fonte de verdade — duas cópias podem divergir e fazer um
 * lado tratar o ambiente como dev e o outro como produção.
 */
final class Profiles {

    private static final String DEV = "dev";
    private static final String TEST = "test";

    private Profiles() {
    }

    /** {@code true} se o profile {@code dev} ou {@code test} está ativo. */
    static boolean isDevLike(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> p.equals(DEV) || p.equals(TEST));
    }
}
