/**
 * Notificações longas legíveis (RCHFLW-188, item 1).
 *
 * <p>O padrão do Mantine é 200px de altura com {@code overflow: hidden} e o conteúdo centralizado: uma notificação
 * mais alta perdia o topo e o fim. O "Execução impedida" de um fluxo com várias referências quebradas passa de mil
 * caracteres — o título saía da tela e a lista de correções terminava no meio de uma frase, numa notificação sem
 * fechamento automático que cobria Publicar e Executar.</p>
 */

/** Altura máxima de uma notificação, passada ao {@code <Notifications>} do App. */
export const NOTIFICATION_MAX_HEIGHT = '80vh'

/**
 * Os estilos de uma notificação cujo texto pode ser longo: a descrição rola dentro de si, então título e texto cabem
 * sempre em {@link NOTIFICATION_MAX_HEIGHT} — nada cortado — e a notificação não cresce até cobrir a tela. A
 * mensagem continua sendo o texto (quem lê o {@code message} segue lendo uma string).
 */
export const LONG_NOTIFICATION_STYLES = {
  description: {
    maxHeight: '45vh',
    overflowY: 'auto' as const,
    whiteSpace: 'pre-wrap' as const,
    overflowWrap: 'anywhere' as const,
  },
}
