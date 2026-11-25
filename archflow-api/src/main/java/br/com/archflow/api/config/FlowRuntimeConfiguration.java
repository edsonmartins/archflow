package br.com.archflow.api.config;

import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.FlowStepFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * O leitor do documento de workflow como bean do servidor.
 *
 * <p>O {@link DefaultWorkflowDeserializer} foi para o archflow-flow-runtime, sem Spring (RCHFLW-37), para o
 * runner standalone e o SDK lerem o documento pelo mesmo código. Era um {@code @Component} com a
 * propriedade no construtor; aqui o servidor o declara com a mesma propriedade — o comportamento é o de
 * antes.</p>
 */
@Configuration
public class FlowRuntimeConfiguration {

    /**
     * @param requireAllowlist {@code archflow.components.require-allowlist}: um fluxo sem
     *                         {@code configuration.allowedComponents} não resolve componente nenhum
     */
    @Bean
    public DefaultWorkflowDeserializer defaultWorkflowDeserializer(
            FlowStepFactory stepFactory,
            @Value("${archflow.components.require-allowlist:false}") boolean requireAllowlist) {
        return new DefaultWorkflowDeserializer(stepFactory, requireAllowlist);
    }
}
