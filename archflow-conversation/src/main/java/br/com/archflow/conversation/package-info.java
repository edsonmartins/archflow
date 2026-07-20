/**
 * Camada conversacional do archflow: suspend/resume, memória episódica,
 * guardrails, governança e sumarização de histórico.
 *
 * <h2>Status de integração: biblioteca OPT-IN (não ligada ao servidor)</h2>
 *
 * <p><strong>Importante (auditoria de homologação, item 7.7 do
 * {@code docs/PLANO_HOMOLOGACAO.md}):</strong> os componentes abaixo são
 * fornecidos como <em>biblioteca</em> e ainda <strong>não estão ligados ao
 * caminho de execução do servidor {@code archflow-api}</strong>. Nenhum deles
 * é invocado automaticamente quando um workflow ou agente executa via REST —
 * quem quiser usá-los hoje precisa instanciá-los e conectá-los
 * programaticamente na própria aplicação:
 *
 * <ul>
 *   <li>{@link br.com.archflow.conversation.guardrail.GuardrailChain} —
 *       cadeia de guardrails de entrada/saída; nada no servidor a executa
 *       antes/depois das chamadas de LLM.</li>
 *   <li>{@link br.com.archflow.conversation.memory.EpisodicMemory} —
 *       memória episódica de longo prazo; o servidor não grava nem consulta
 *       episódios durante execuções.</li>
 *   <li>{@link br.com.archflow.conversation.orchestrator.ConversationOrchestrator} —
 *       orquestração de conversas (turnos, estado, sumarização); o fluxo REST
 *       de execução não passa por ele.</li>
 *   <li>{@link br.com.archflow.conversation.governance.GovernanceResolver} —
 *       resolução de perfis de governança; os perfis expostos em
 *       {@code /api/workflow/governance-profiles} são catálogo, não são
 *       aplicados ao runtime por este resolver.</li>
 * </ul>
 *
 * <p>O mesmo vale para a sumarização de histórico
 * ({@code br.com.archflow.conversation.summary}): é opt-in.
 *
 * <p>Quando esses componentes forem integrados ao runtime do servidor, esta
 * nota deve ser atualizada/removida junto com o item 7.7 do plano de
 * homologação.
 */
package br.com.archflow.conversation;
