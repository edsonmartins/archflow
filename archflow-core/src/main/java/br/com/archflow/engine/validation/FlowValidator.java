package br.com.archflow.engine.validation;

import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.engine.exceptions.FlowValidationException;

/**
 * Responsável por validar a estrutura e integridade de um fluxo.
 * Verifica conexões, parâmetros e configurações antes da execução.
 */
public interface FlowValidator {
    /**
     * Valida um fluxo completo.
     *
     * @param flow fluxo a ser validado
     * @throws FlowValidationException se houver erros de validação
     */
    void validate(Flow flow) throws FlowValidationException;

    /**
     * Valida um passo específico do fluxo.
     *
     * @param step passo a ser validado
     * @param context contexto do fluxo para validação
     * @throws FlowValidationException se houver erros de validação
     */
    void validateStep(FlowStep step, ValidationContext context) throws FlowValidationException;
}

