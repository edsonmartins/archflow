# archflow-ui — Análise Competitiva de UX (2026-07-02)

Análise rigorosa do frontend frente aos líderes de mercado (n8n, Dify, Langflow, Flowise, Zapier), com foco em facilidade de uso e adoção. Complementa o audit de 6 ondas de 2026-06 (que cobriu higiene de UI: confirmações, DataTable, PageHeader, StatusBadge, a11y) — esta análise cobre **competitividade de produto e identidade visual**.

---

## 1. Veredito executivo

O archflow-ui tem superfícies genuinamente competitivas (ChatPanel/ToolCallBlock, CopilotAppOperator, anotações de canvas, galeria de templates, TracesPage) e uma identidade tipográfica intencional (Bricolage Grotesque + DM Sans/DM Mono). Porém, **falha nos dois loops que definem adoção nesta categoria**:

1. **Loop de confiança (testar/depurar)**: o botão Run do editor é um stub (`WorkflowEditorPage.tsx:258-267` — fabrica `exec-${Date.now()}`, nunca chama API nem SSE; pills de status e edges animadas nunca disparam). O `ExecutionDetailPage` despeja `JSON.stringify` do objeto inteiro (`:104`) sem I/O por step, timings ou retry. Em 2026, "executar um nó e inspecionar input/output" é o gesto central de n8n, Dify, Langflow, Flowise e Zapier — todos têm.
2. **Loop de primeira vitória (onboarding)**: usuário novo cai numa lista vazia com um único CTA "criar do zero". Templates existem mas não são ofertados no momento de criação nem no canvas vazio. Não há geração de workflow por prompt visível (a infra existe — `CanvasApi` do copilot — mas está escondida num sidebar fechado por padrão).

A boa notícia: boa parte do gap é **fiação, não construção** — SSE, ChatPanel, templates e CanvasApi já existem e precisam ser conectados às superfícies certas.

---

## 2. Table stakes do mercado (2025–2026) vs archflow

| # | Table stake (todos os 5 líderes têm) | archflow hoje |
|---|---|---|
| 1 | Palette de nós pesquisável + "+" na edge/handle + click-to-add | ✗ Drag-only (`FlowCanvas.tsx:222-269`); busca só filtra texto do palette |
| 2 | Execução parcial/por nó com painéis Input/Output | ✗ Run é stub; tabs Logs/Output existem mas nunca populam |
| 3 | Dados pinados/mock para iterar sem re-executar LLM | ✗ Inexistente |
| 4 | Histórico de execuções com drill-down por step + retry/replay | ✗ `ExecutionDetailPage` = dump de JSON cru |
| 5 | Geração de workflow por prompt ("build with AI") | ◐ Existe via CopilotKit global (`CanvasApi`), invisível no editor |
| 6 | Galeria de templates ofertada na criação | ◐ Galeria existe (página separada); não surfaceada no editor/empty state |
| 7 | Sticky notes / anotações no canvas | ✓ StickyNote, GroupFrame, SectionDivider |
| 8 | Undo/redo + copy/paste/duplicate + autosave | ✗ Nenhum dos três (autosave comentado, `WorkflowEditorPage.tsx:270-273`) |
| 9 | Light + dark mode | ✓ Toggle funcional (admin shell sem toggle) |
| 10 | Ícones por integração/categoria + config em painel lateral | ✓ ShapedNode é forte; mas minimap usa OUTRA paleta de cores |
| 11 | Consumo por flow: chat embed + link hosted + snippet de API | ✗ Inexistente (web-component existe no código mas sem UX de publish) |
| 12 | MCP nas duas direções | ◐ Consome MCP (página admin órfã); não expõe flows como MCP |
| 13 | HITL pause/approve | ✓ Approval queue + nó de approval (mas campo de comentário é morto) |

**Cobertura: ~4,5 de 13.** Os gaps 1–4 e 8 são os que mais doem na demo/avaliação de adoção.

---

## 3. Achados por severidade

### P0 — Bloqueadores de adoção (o avaliador desiste aqui)

1. **Run do editor não executa nada** — `WorkflowEditorPage.tsx:258-267`. Ligar ao endpoint real + SSE (`services/event-stream.ts` já existe) para acender `executionState` no store → pills/edges animadas já implementados no `ShapedNode`/`FlowEdge` passam a funcionar de graça.
2. **ExecutionDetailPage é a superfície mais fraca do app** — sem I/O por step, sem timings, sem retry (`:104`). O padrão a copiar já está NO PRÓPRIO APP: `ToolCallBlock.tsx:105-127` (blocos colapsáveis com input/output JSON, status, duração).
3. **Editor sem undo/redo, copy/paste, duplicate, autolayout** — grep confirma zero ocorrências. Único atalho: Delete. (n8n tem Tidy-up + Command Bar; Dify tem auto-organize + Cmd+K.)
4. **Adição de nó é drag-only** — sem click-to-add, sem "+" na edge, sem menu ao soltar conexão no vazio.

### P1 — Funil de adoção e identidade

5. **Onboarding sem primeira vitória**: empty state do WorkflowList (`:79-97`, bem feito) não oferece templates nem "gerar com IA"; sem tour; CopilotSidebar `defaultOpen={false}`.
6. **Sem cmd+K / busca global** — zero refs a spotlight/cmdk/hotkeys em 30+ páginas.
7. **Sem dashboard pós-login** — home é a lista de workflows; nenhuma visão de atividade/saúde para usuário comum.
8. **Identidade visual fragmentada**:
   - Dois azuis de marca: Mantine `archBlue.5 #185FA5` (`theme.ts`, LoginPage) vs CSS `--blue #2563EB` (App.css, AdminLayout logo).
   - Três sistemas de token concorrentes: `--color-*`, `--mantine-color-*`, legado `--bg2/--text2/--border2` — misturados até em componentes vizinhos.
   - 54 hexes hardcoded em 14 arquivos; minimap (`FlowCanvas.tsx:387-395`) usa paleta DIFERENTE de `NODE_CATEGORIES` (`constants.ts:3-67`).
   - Emojis vazam apesar da política: `PALETTE_NODES` (`constants.ts:113-158`) carrega 🤖🧠💬⚡ que aparecem no header do PropertyPanel (`:114`); glifos ◌ ✓ ✕ no pill de execução.
   - Top bar do editor é `<div>/<button>` cru com inline styles; só o Run é Mantine (`WorkflowEditorPage.tsx:284-390`).
9. **JSON cru para usuário final em 6 lugares**: ExecutionDetail, AgentPlayground (`:134`), LiveEvents (`:198`), MCP inputSchema (`:64`), ApprovalDetail proposal (`:205`), TraceDetail attributes (`:203`). Falta um componente `JsonViewer` (árvore colapsável).
10. **Navegação**: sidebar plana de 12 itens sem agrupamento (5 são "labs"); rotas órfãs sem entrada em NENHUM menu (Skills, MCP, BrainSentry, Linktor, Triggers, Scoped API Keys — `App.tsx:123-130`); ícone do "Editor" é IconLayoutDashboard; app sidebar não linka para /admin.

### P2 — Controles mortos, bugs e polish

11. **Comentário de aprovação descartado**: `ApprovalDetailPage.tsx:214-221` coleta `comment`, `decide()` (`:83-88`) não envia.
12. **Marketplace "Not installed" permanentemente disabled** (`MarketplacePage.tsx:157-159`); instalação só por modal de caminho de manifest — não é uma loja.
13. **Bug de cor no NodeHeader**: `NODE_CATEGORIES[nodeData.nodeType]` (`PropertyPanel.tsx:107`) recebe componentId (`llm-chat`), não categoria → quase sempre cai no cinza `io`.
14. **CopilotAssistantPage é placeholder** com exemplos PT hardcoded fora do i18n (`:26-29`).
15. **TraceDetail sem árvore parent/child** — spans planos, causalidade perdida (`:122-171`).
16. **Densidade/loading inconsistentes**: 3 convenções de padding de página; skeletons só nas listas DataTable, resto é `<Loader>` ou texto "Loading…"; empty states sem CTA (só WorkflowList tem).
17. **Hit targets pequenos**: PanelToggle 26×26, tabs 4px de padding vertical, handles 11px; bordas de 0.5px.
18. **AgentPlayground** não reusa o ChatPanel — é teste de fio com JSON cru, não um playground estilo Langflow.
19. **`simulateExecution`** no store não é usado em lugar nenhum (código morto).
20. **Sync frágil store↔React Flow** (`fromStoreRef` + filtragem defensiva, `FlowCanvas.tsx:103-155`) — fonte provável de edições perdidas; candidato a refactor quando mexer no editor.

---

## 4. Forças a preservar/alavancar

- **ChatPanel + ToolCallBlock (4.5/5)** — streaming SSE completo, transparência de tool calls. É o template interno para ExecutionDetail e para o playground.
- **CopilotAppOperator (4/5)** — agente que opera o app (navega, cria flows, adiciona nós). Nenhum concorrente tem isso como operador global: é um DIFERENCIAL se for surfaceado (hoje está escondido).
- **Anotações de canvas** (sticky/group/divider) — table stake já coberto, à frente do Zapier.
- **Templates com carga em 3 camadas** (API → JSON público → bundle) — arquitetura pronta, falta distribuição na UX.
- **CanvasOutline acessível por teclado** — raro no mercado; manter no redesign.
- **i18n com paridade total pt-BR/en (1368 chaves)** e dark/light funcionais.
- **ShapedNode** — visual de nó forte (faixa de categoria, badge com gradiente, chips de config).

---

## 5. Plano recomendado (ondas de competitividade)

### Onda A — Loop de confiança (maior ROI, ~fiação)
1. Run real: POST de execução + subscribe SSE → `executionState` no store (pills/edges já prontos).
2. Tabs Logs/Output do PropertyPanel populados pelo stream por nó.
3. ExecutionDetail reescrito no padrão ToolCallBlock: lista de steps com I/O colapsável, duração, status; botão retry.
4. Corrigir: comentário de aprovação enviado; bug do NodeHeader; TraceDetail com indentação por parent.

### Onda B — Editor table stakes
5. Undo/redo (ex.: `zundo` sobre o Zustand) + copy/paste/duplicate + atalhos documentados (?-overlay).
6. Click-to-add no palette + "+" na edge + menu de busca ao soltar conexão no vazio.
7. Autolayout (dagre/elk) como botão "Organizar".
8. Autosave (draft) reativado + indicador; considerar draft-vs-publish (padrão n8n 2.0/Zapier).

### Onda C — Funil de adoção
9. Empty state e "Novo workflow" com 3 caminhos: **Template / Gerar com IA / Do zero** (modal de criação estilo Langflow).
10. Prompt bar "Build with AI" dentro do editor usando o CanvasApi existente.
11. Cmd+K via `@mantine/spotlight` (navegar páginas, abrir workflows, ações).
12. Sidebar agrupada: Build (Workflows, Templates, Editor) / Run (Executions, Conversations, Approvals) / Extend (Marketplace, Skills, MCP, Triggers) / Labs (playgrounds colapsados) — resgata as rotas órfãs.
13. Dashboard home simples (atividade recente, execuções falhas, CTA de criação).

### Onda D — Identidade visual e consistência
14. Um único azul de marca; fundir os 3 sistemas de token em um (mapeado no tema Mantine); eliminar os 54 hexes; minimap = NODE_CATEGORIES.
15. Matar emojis do `PALETTE_NODES` usando o mapa Tabler de `nodeIcons.tsx`; substituir glifos ◌✓✕ por ícones.
16. Top bar do editor 100% Mantine; hit targets ≥ 36-44px; bordas 1px.
17. Componente `JsonViewer` (árvore colapsável + copy) substituindo os 6 dumps de JSON.
18. Padding de página unificado (PageContainer), skeletons de card, empty states com CTA em todas as listas.

### Onda E — Publicação/consumo (paridade Dify/Flowise)
19. Aba "Publicar" por workflow: endpoint + snippet (curl/JS/Python), link de chat hosted, embed do web-component já existente.
20. Marketplace como loja real (instalar do card, ícones, categorias, destaque) ou rebaixar a "Extensões" admin.
21. Expor flows como MCP server (diferencial barato dado o suporte MCP existente).

---

## 6. Quick wins (dias, não semanas)

- Fix comentário de aprovação (payload de `decide()`).
- Fix cor do NodeHeader (lookup por categoria, não componentId).
- Minimap usando cores de `NODE_CATEGORIES`.
- Remover emojis do palette/PropertyPanel (mapa Tabler já existe).
- Agrupar sidebar + ícone correto do Editor + linkar rotas órfãs.
- Empty states com CTA (Templates→galeria, Marketplace→busca, Executions→rodar um flow).
- i18n: `TemplatesPage.tsx:188` ("steps"), exemplos do CopilotAssistantPage.
- Esconder ou rotular como beta: AgentPlayground/CopilotAssistantPage (superfícies 1-2/5 depõem contra o produto em demo).
