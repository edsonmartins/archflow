# ArchFlow Frontend Redesign — Plano de Implementação

## Contexto e decisão arquitetural

O `archflow-ui` migra de um Web Component customizado (CanvasManager + CanvasRenderer)
para `@xyflow/react` como canvas principal. O Web Component `<archflow-designer>` é
mantido como wrapper fino — ele monta o FlowCanvas React via `ReactDOM.createRoot()`
dentro do Shadow DOM e expõe a mesma API pública (atributos, métodos, eventos).

**Princípio:** o canvas vive uma vez (em React). O Web Component é um adaptador.

---

## Dependências a instalar

```bash
npm uninstall reactflow
npm install @xyflow/react
npm install @mantine/notifications
npm install zustand          # já existe — verificar versão >= 5
```

> `reactflow` (v11) → `@xyflow/react` (v12). API quase idêntica,
> mas o import muda: `import { ReactFlow } from '@xyflow/react'`
> e o CSS: `import '@xyflow/react/dist/style.css'`

---

## Estrutura de arquivos a criar / modificar

```
src/
├── components/
│   ├── FlowCanvas/                    ← NOVO — canvas principal
│   │   ├── FlowCanvas.tsx             ← componente raiz com ReactFlow
│   │   ├── types.ts                   ← FlowNodeData, WorkflowData, etc.
│   │   ├── constants.ts               ← categorias, paleta, cores de execução
│   │   ├── nodes/
│   │   │   ├── BaseNode.tsx           ← nó base com handle, badge de execução
│   │   │   ├── AgentNode.tsx          ← wrapper azul
│   │   │   ├── ControlNode.tsx        ← wrapper roxo
│   │   │   ├── DataNode.tsx           ← wrapper teal
│   │   │   ├── ToolNode.tsx           ← wrapper coral
│   │   │   ├── VectorNode.tsx         ← wrapper amber
│   │   │   └── IONode.tsx             ← wrapper cinza
│   │   ├── edges/
│   │   │   └── FlowEdge.tsx           ← edge com animação de execução
│   │   └── store/
│   │       └── useFlowStore.ts        ← Zustand: execução + seleção
│   │
│   ├── NodePalette.tsx                ← MODIFICAR — drag compatível com xyflow
│   ├── PropertyPanel.tsx              ← NOVO — substitui PropertyEditor
│   └── AppLayout.tsx                  ← MODIFICAR — navbar redesenhada
│
├── pages/
│   ├── WorkflowEditor.tsx             ← MODIFICAR — integra FlowCanvas
│   ├── WorkflowList.tsx               ← MODIFICAR — cards em vez de tabela
│   └── Login.tsx                      ← MODIFICAR — logo + identidade
│
├── web-component/
│   └── ArchflowDesigner.ts            ← MODIFICAR — wrapper React fino
│
└── theme.ts                           ← NOVO — Mantine theme customizado
```

---

## Fase 1 — Fundação visual (3 dias)

### 1.1 theme.ts — criar

```ts
import { createTheme, MantineColorsTuple } from '@mantine/core'

const archBlue: MantineColorsTuple = [
  '#E6F1FB', '#B5D4F4', '#85B7EB', '#55A0E2', '#378ADD',
  '#185FA5', '#0C447C', '#042C53', '#021D38', '#010F1D',
]

export const theme = createTheme({
  primaryColor:  'archBlue',
  primaryShade:  5,
  colors:        { archBlue },
  fontFamily:    "'DM Sans', system-ui, sans-serif",
  fontFamilyMonospace: "'DM Mono', 'Fira Code', monospace",
  defaultRadius: 'md',
  components: {
    Button: { defaultProps: { radius: 'md' } },
    Input:  { defaultProps: { radius: 'md' } },
    Paper:  { defaultProps: { radius: 'lg' } },
  },
})
```

### 1.2 main.tsx — aplicar tema e dark mode

```tsx
// Adicionar ao MantineProvider:
import { theme } from './theme'
import { useLocalStorage } from '@mantine/hooks'
import '@mantine/notifications/styles.css'
import { Notifications } from '@mantine/notifications'

function App() {
  const [colorScheme, setColorScheme] = useLocalStorage<'light' | 'dark'>({
    key: 'archflow-color-scheme',
    defaultValue: 'light',
  })

  return (
    <MantineProvider theme={theme} forceColorScheme={colorScheme}>
      <Notifications />
      {/* ... */}
    </MantineProvider>
  )
}
```

### 1.3 AppLayout.tsx — redesign da navbar

Substituir a navbar horizontal por vertical com:
- Logo SVG + "Archflow" + badge de versão
- NavLink items com ícones Tabler
- Avatar do usuário na base
- Toggle de dark mode funcional (usa setColorScheme do contexto)

---

## Fase 2 — Editor de fluxos (12 dias)

### 2.1 Instalar e configurar @xyflow/react

```bash
npm install @xyflow/react
```

Adicionar ao CSS global (index.css ou App.css):
```css
/* Animação de edge durante execução */
@keyframes archflow-dash {
  to { stroke-dashoffset: -18; }
}

/* Pulse para badge de running */
@keyframes pulse {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.6; }
}

/* Override de estilos do xyflow para alinhar com design system */
.react-flow__controls-button {
  background: var(--color-background-primary) !important;
  border: 0.5px solid var(--color-border-secondary) !important;
  border-radius: 6px !important;
  box-shadow: none !important;
}

.react-flow__minimap {
  border-radius: 8px !important;
  border: 0.5px solid var(--color-border-secondary) !important;
  box-shadow: none !important;
}
```

### 2.2 Criar os arquivos do FlowCanvas (ver /src/components/FlowCanvas/)

Os arquivos já estão gerados neste PR:
- `FlowCanvas.tsx` — componente raiz
- `types.ts` — tipos compartilhados
- `constants.ts` — NODE_CATEGORIES, PALETTE_NODES, EXECUTION_STATUS_COLORS
- `nodes/BaseNode.tsx` — nó base com handles e badge de execução
- `nodes/index.ts` — factory que cria os 6 nós por categoria
- `edges/FlowEdge.tsx` — edge com animação durante execução
- `store/useFlowStore.ts` — Zustand store de execução e seleção

### 2.3 Modificar WorkflowEditor.tsx

Substituir o `<archflow-designer>` Web Component por `<FlowCanvas>` diretamente.
O Web Component continua existindo para uso externo, mas o `archflow-ui` usa o React puro.

### 2.4 Modificar ArchflowDesigner.ts (Web Component)

O wrapper já foi atualizado para montar o FlowCanvas via `ReactDOM.createRoot()`.
Remover o import do CanvasManager, CanvasRenderer e demais classes do web-component/canvas/.

### 2.5 NodePalette — atualizar drag

O atributo de drag muda de formato. Agora usa:
```ts
event.dataTransfer.setData('application/archflow-node', JSON.stringify({
  type, componentId, label, category
}))
```

E o canvas ouve em `onDrop` usando `screenToFlowPosition` do React Flow instance.

### 2.6 Integração com execução via SSE

Quando o backend emitir eventos de execução, usar:

```ts
const es = new EventSource(`/api/executions/${execId}/events`)

es.onmessage = (e) => {
  const event = JSON.parse(e.data)
  if (event.type === 'NODE_STARTED') {
    useFlowStore.getState().updateNodeStatus(event.nodeId, {
      status: 'running',
      startedAt: Date.now(),
    })
  }
  if (event.type === 'NODE_COMPLETED') {
    useFlowStore.getState().updateNodeStatus(event.nodeId, {
      status: 'success',
      durationMs: event.durationMs,
      output: event.output,
    })
  }
  if (event.type === 'EXECUTION_FINISHED') {
    useFlowStore.getState().finishExecution()
    es.close()
  }
}
```

---

## Fase 3 — Limpeza do Web Component (2 dias)

Após o FlowCanvas React estar funcionando no `archflow-ui`, limpar:

1. `src/web-component/canvas/` — remover CanvasManager.ts, CanvasRenderer.ts, Minimap.ts
2. `src/web-component/nodes/` — remover NodeRegistry.ts, CustomNodeAPI.ts, node-types.ts
3. `src/web-component/execution/` — remover ExecutionStore.ts, ExecutionPanel.ts

O `ArchflowDesigner.ts` passa a ser o único arquivo do web-component/ que importa código
de fora (FlowCanvas + useFlowStore).

**Importante:** a API pública do Web Component não muda:
- Atributos: `workflow-id`, `theme`, `readonly`, `show-minimap`, `show-grid`, `width`, `height`
- Métodos: `setWorkflow()`, `getWorkflow()`, `addNode()`, `clearCanvas()`, `zoomTo()`, `fitView()`
- Eventos: `node-selected`, `selection-cleared`, `workflow-saved`, `workflow-executed`,
  `node-added`, `node-removed`, `connection-added`

---

## Fase 4 — Telas restantes + Admin de tenants (10 dias)

### WorkflowList.tsx
- Migrar de tabela para grid de cards
- Cada card: ícone por categoria dominante do workflow, nome, descrição, badges de status,
  steps count, versão, data de atualização, ações (execute, edit, delete)
- Empty state com SVG + CTA
- Search com debounce 300ms

### Login.tsx
- Adicionar logo SVG acima do card
- Tagline "Agent orchestration platform"
- Remover bg branco puro → usar var(--color-background-tertiary)

### ExecutionHistory.tsx
- Linhas com ícone de status (✓ / ✕ / ◌)
- Duração formatada (45s, 1m 23s)
- Expandir linha para ver logs por nó inline (Accordion)
- Badge de execução com animação quando RUNNING

---

## Fase 5 — Admin de Tenants (8 dias)

### Contexto e hierarquia de roles

O ArchFlow tem dois níveis de administração com escopos completamente diferentes:

| Role | Quem é | O que vê |
|---|---|---|
| **Superadmin** | IntegrAllTech (Edson + equipe) | Todos os tenants, configuração global, uso agregado, billing |
| **Admin do tenant** | Admin de cada cliente (ex: João Silva na Rio Quality) | Apenas seu workspace: usuários, workflows, execuções, API keys |

O isolamento de dados é por `tenant_id` em todas as tabelas — o frontend nunca exibe dados de outro tenant mesmo que o usuário tente manipular parâmetros de URL.

O superadmin pode fazer **impersonation** (entrar como tenant) sem login separado — o contexto muda visualmente (banner laranja de aviso + sidebar diferente) e todas as chamadas de API passam a incluir o `tenant_id` do tenant impersonado.

---

### 5.1 Rotas do admin

```
/admin                           → redireciona para /admin/tenants (superadmin)
                                    ou /admin/workspace (admin de tenant)
/admin/tenants                   → lista de tenants (superadmin only)
/admin/tenants/new               → formulário de criação de tenant
/admin/tenants/:id               → detalhe + edição de tenant
/admin/tenants/:id/impersonate   → inicia impersonation session

/admin/workspace                 → dashboard do workspace (admin de tenant)
/admin/workspace/users           → gestão de usuários do tenant
/admin/workspace/keys            → API keys do tenant
/admin/workspace/models          → modelos LLM configurados
/admin/workspace/usage           → uso do plano do tenant
/admin/workspace/notifications   → configuração de notificações (Linktor)

/admin/global/models             → modelos disponíveis na plataforma (superadmin)
/admin/global/templates          → templates globais de workflow (superadmin)
/admin/global/audit              → audit log geral (superadmin)
/admin/global/billing            → uso e custo por tenant (superadmin)
```

---

### 5.2 Estrutura de arquivos

```
src/
├── pages/
│   └── admin/
│       ├── superadmin/
│       │   ├── TenantList.tsx          ← lista com stat cards + tabela
│       │   ├── TenantNew.tsx           ← formulário de criação
│       │   ├── TenantDetail.tsx        ← edição + suspender + impersonation
│       │   ├── GlobalConfig.tsx        ← modelos, limites, toggles, audit
│       │   └── UsageBilling.tsx        ← consumo e custo por tenant
│       └── tenant/
│           ├── WorkspaceOverview.tsx   ← stat cards de uso do plano
│           ├── UserManagement.tsx      ← tabela de usuários + convite
│           ├── ApiKeys.tsx             ← listagem e criação de chaves
│           ├── ModelConfig.tsx         ← modelos disponíveis (read-only, definido pelo superadmin)
│           └── UsagePlan.tsx           ← uso atual vs. limite do plano
│
├── components/
│   └── admin/
│       ├── AdminLayout.tsx             ← shell com sidebar contextual por role
│       ├── ImpersonationBanner.tsx     ← banner laranja quando superadmin está em tenant
│       ├── TenantCard.tsx              ← card de tenant com usage bar
│       ├── PermissionMatrix.tsx        ← grid de permissões por papel
│       ├── ApiKeyRow.tsx               ← linha de chave com mask + copy + revoke
│       ├── UsageBar.tsx                ← barra de progresso de consumo
│       └── LimitEditor.tsx             ← campo de limite com label + unit
│
├── stores/
│   └── useTenantStore.ts              ← tenant atual, impersonation state, role
│
└── services/
    └── admin-api.ts                   ← chamadas REST para /api/admin/*
```

---

### 5.3 AdminLayout.tsx — sidebar contextual por role

A sidebar muda completamente dependendo do papel do usuário logado. O `useTenantStore` expõe `currentRole: 'superadmin' | 'tenant_admin' | 'editor' | 'viewer'` e `impersonating: TenantInfo | null`.

```tsx
// Lógica de sidebar
const { currentRole, impersonating } = useTenantStore()

const isSuperadmin = currentRole === 'superadmin' && !impersonating
// Quando superadmin está em impersonation, mostra sidebar do tenant
const showTenantSidebar = currentRole === 'tenant_admin' || impersonating !== null
```

**Sidebar do superadmin:**
- Tenants
- Configuração global (modelos LLM, limites por plano, toggles)
- Templates globais
- Uso & billing
- Audit log
- Chaves de API da plataforma

**Sidebar do admin de tenant:**
- Usuários
- Workflows (link para /workflows)
- Execuções (link para /executions)
- Modelos e providers (read-only)
- API Keys
- Notificações
- Uso do plano

---

### 5.4 ImpersonationBanner.tsx

Sempre visível quando superadmin entrou em um tenant. Não pode ser fechado enquanto a sessão de impersonation estiver ativa.

```tsx
export function ImpersonationBanner({ tenant }: { tenant: TenantInfo }) {
  const { exitImpersonation } = useTenantStore()
  return (
    <div style={{ background: '#FAEEDA', border: '1px solid #BA7517', ... }}>
      ⚑ Visualizando como Admin do tenant <b>{tenant.name}</b>.
      Todas as ações são registradas no audit log.
      <button onClick={exitImpersonation}>Voltar ao superadmin →</button>
    </div>
  )
}
```

> **Importante para o backend:** quando `impersonating` está ativo, o frontend envia o header
> `X-Impersonate-Tenant: {tenantId}` em todas as requisições. O backend valida que o token
> JWT pertence a um superadmin antes de aceitar o header.

---

### 5.5 TenantList.tsx — lista de tenants (superadmin)

**Stat cards no topo (4 cards):**
- Tenants ativos
- Execuções hoje (agregado de todos os tenants)
- Tokens consumidos este mês
- Tenants em trial (com alerta se algum expira em < 7 dias)

**Tabela de tenants:**

| Coluna | Conteúdo |
|---|---|
| Tenant | Logo colorido (iniciais) + nome + tenant_id em mono |
| Status | Badge: Ativo / Trial / Suspenso |
| Plano | Enterprise / Professional / Internal / Trial |
| Workflows | Contagem |
| Execuções/dia | Número |
| Uso de tokens | Barra de progresso 100px + percentual |
| Criado em | Mês e ano |
| Ações | Botões: Entrar (impersonation) · Detalhes |

**Ações de linha:**
- **Entrar** → inicia impersonation, redireciona para `/admin/workspace`
- **Detalhes** → abre `TenantDetail.tsx` com edição de limites e suspensão

---

### 5.6 TenantNew.tsx — criar tenant

Formulário dividido em quatro blocos:

**Identidade:**
- Nome do tenant (string)
- Tenant ID (slug auto-gerado, editável, validado como único)
- E-mail do admin (usado para envio do convite de primeiro acesso)
- Setor / vertical (select)

**Plano e limites:**
- Plano (Trial 30d / Professional / Enterprise / Internal)
- Data de vencimento
- Limites customizados (sobrescrevem o default do plano):
  - Execuções por dia
  - Tokens por mês
  - Workflows simultâneos
  - Usuários máximos

**Modelos LLM permitidos:**
- Tag input com os modelos disponíveis na plataforma
- O admin do tenant só pode usar modelos desta lista

**Templates globais disponíveis:**
- Checkboxes com os templates globais publicados
- Templates marcados ficam disponíveis para instanciação no tenant

**Painel lateral de resumo** (sticky) — mostra as configurações selecionadas antes de confirmar.

Ao criar: backend gera o tenant com `tenant_id` único, cria o usuário admin com o e-mail fornecido, envia e-mail de primeiro acesso com link temporário.

---

### 5.7 UserManagement.tsx — usuários do tenant

**Papéis disponíveis:**

| Papel | Descrição |
|---|---|
| Admin | Gerencia usuários, configura workspace, cria/edita/executa workflows |
| Editor | Cria, edita e executa workflows. Não gerencia usuários ou API keys |
| Viewer | Lê workflows e histórico de suas próprias execuções |

**Matriz de permissões** (componente `PermissionMatrix`):

| Permissão | Admin | Editor | Viewer |
|---|---|---|---|
| Ver workflows | ✓ | ✓ | ✓ |
| Criar/editar workflows | ✓ | ✓ | — |
| Executar workflows | ✓ | ✓ | — |
| Ver histórico de execuções | ✓ | ✓ | Parcial¹ |
| Gerenciar usuários | ✓ | — | — |
| Configurar modelos LLM | Parcial² | — | — |
| Gerenciar API Keys | ✓ | — | — |

> ¹ Viewer vê apenas execuções que ele mesmo iniciou
> ² Admin seleciona dentre os modelos aprovados pelo superadmin — não pode adicionar novos

**Tabela de usuários:**
- Avatar com iniciais coloridas
- Nome + e-mail
- Papel (badge: Admin / Editor / Viewer)
- Último acesso
- Workflows criados
- Status (Ativo / Convite pendente)
- Ações: Editar papel · Revogar convite (se pendente) · Remover

**Fluxo de convite:**
1. Admin clica em "+ Convidar usuário"
2. Modal: e-mail + papel
3. Backend envia e-mail com link temporário (24h)
4. Usuário acessa o link, define senha, é redirecionado ao workspace
5. Linha na tabela aparece com status "Convite pendente" até o aceite

---

### 5.8 ApiKeys.tsx — chaves de API do tenant

Três categorias de chave com escopos diferentes:

| Tipo | Prefixo | Escopo | Uso |
|---|---|---|---|
| Produção | `af_live_` | Leitura + escrita + execução | Backend do cliente (VendaX, etc.) |
| Homologação | `af_test_` | Leitura + escrita + execução (sandbox) | Ambiente de testes do cliente |
| Web Component | `af_pub_` | Apenas leitura de workflow + execução read-only | `<archflow-designer>` embeddable |

> A chave do Web Component (`af_pub_`) é propositalmente limitada — ela pode carregar a definição do workflow e iniciar execuções, mas não pode criar/editar workflows nem acessar dados de outros tenants. É segura para ser exposta no frontend público.

**Exibição:** valor mascarado (`af_live_rq_••••••••3f2a`) com botão de copiar. O valor completo só é exibido uma vez na criação — depois não é mais recuperável (armazenado como hash no banco).

---

### 5.9 GlobalConfig.tsx — configuração global (superadmin)

**Modelos LLM disponíveis na plataforma:**

Tabela com: nome do modelo (mono), provider, status (Ativo/Beta/Deprecado), custo por 1M tokens input, custo por 1M tokens output, ações (ativar/desativar).

**Limites default por plano:**

Formulário de campos `LimitEditor` para cada plano:
- Enterprise: execuções/dia, tokens/mês, workflows simultâneos, usuários
- Professional: idem com valores menores
- Trial: idem com período de dias e limites reduzidos

**Toggles de funcionalidades globais:**
- Permitir modelos locais (Ollama / RTX)
- Human-in-the-loop habilitado
- Brain Sentry (memória de longo prazo via FalkorDB)
- Modo debug — trace completo por nó
- Notificações via Linktor (WhatsApp)
- Registro de audit log de ações do usuário

**Audit log recente** (últimas 10 entradas com link para log completo):
- Timestamp em mono
- Actor (superadmin ou `tenant_id/user_id`)
- Ação com contexto (ex: "Atualizou limite tokens de Rio Quality: 5M → 10M")

---

### 5.10 UsageBilling.tsx — uso e custo por tenant (superadmin)

**Stat cards agregados:** execuções no mês, tokens consumidos, custo estimado, latência média.

**Tabela por tenant:** execuções, tokens input, tokens output, custo estimado, % do total, limite do plano.

**Período:** selector de mês/ano para navegar no histórico.

**Exportação:** botão que gera CSV com o breakdown por tenant e por modelo.

---

### 5.11 useTenantStore.ts — Zustand store de contexto de admin

```ts
interface TenantStore {
  // Role do usuário logado
  currentRole:   'superadmin' | 'tenant_admin' | 'editor' | 'viewer'
  currentTenant: TenantInfo | null   // null quando superadmin fora de impersonation

  // Impersonation
  impersonating: TenantInfo | null
  startImpersonation: (tenant: TenantInfo) => void
  exitImpersonation:  () => void

  // Tenant do usuário (para admins de tenant)
  tenantLimits:  TenantLimits | null
  tenantUsage:   TenantUsage  | null
}

interface TenantInfo {
  id:     string   // ex: "tenant_rio_quality"
  name:   string   // ex: "Rio Quality"
  plan:   'enterprise' | 'professional' | 'trial' | 'internal'
  status: 'active' | 'trial' | 'suspended'
}

interface TenantLimits {
  executionsPerDay:   number
  tokensPerMonth:     number
  maxWorkflows:       number
  maxUsers:           number
  allowedModels:      string[]
  featuresEnabled:    string[]
}
```

---

### 5.12 Proteção de rotas

```tsx
// ProtectedRoute já existe — estender com role check
<ProtectedRoute requiredRole="superadmin">
  <TenantList />
</ProtectedRoute>

<ProtectedRoute requiredRole="tenant_admin">
  <UserManagement />
</ProtectedRoute>

// Superadmin pode acessar rotas de tenant_admin (via impersonation)
// A lógica de role check precisa considerar: currentRole === 'superadmin' || impersonating !== null
```

---

### 5.13 Novos testes Playwright para o admin

```ts
// Superadmin — criar tenant
test('superadmin cria novo tenant', async ({ page }) => {
  await page.goto('/admin/tenants/new')
  await page.fill('[name="tenantName"]', 'Novo Cliente')
  await page.fill('[name="adminEmail"]', 'admin@novocliente.com.br')
  await page.selectOption('[name="plan"]', 'enterprise')
  await page.click('button:has-text("Criar tenant")')
  await expect(page.locator('.notification')).toContainText('Tenant criado')
})

// Superadmin — impersonation
test('superadmin entra como tenant e vê banner', async ({ page }) => {
  await page.goto('/admin/tenants')
  await page.click('tr:has-text("Rio Quality") button:has-text("Entrar")')
  await expect(page.locator('[data-testid="impersonation-banner"]')).toBeVisible()
  await expect(page.locator('[data-testid="impersonation-banner"]')).toContainText('Rio Quality')
})

// Admin de tenant — convidar usuário
test('admin convida novo usuário', async ({ page }) => {
  await page.goto('/admin/workspace/users')
  await page.click('button:has-text("Convidar usuário")')
  await page.fill('[name="email"]', 'novo@rioquality.com.br')
  await page.selectOption('[name="role"]', 'editor')
  await page.click('button:has-text("Enviar convite")')
  await expect(page.locator('table')).toContainText('Convite pendente')
})

// Viewer não acessa rota de admin
test('viewer é redirecionado ao acessar admin', async ({ page }) => {
  // login como viewer...
  await page.goto('/admin/tenants')
  await expect(page).toHaveURL('/workflows')  // redireciona para home
})
```

---

### 5.14 Checklist da Fase 5

- [ ] `useTenantStore.ts` com roles, impersonation e limites do tenant
- [ ] `AdminLayout.tsx` com sidebar contextual por role
- [ ] `ImpersonationBanner.tsx` com exit e audit trail
- [ ] `ProtectedRoute` atualizado com role check + impersonation
- [ ] `TenantList.tsx` com stat cards + tabela + usage bars
- [ ] `TenantNew.tsx` com formulário 4 blocos + painel de resumo
- [ ] `TenantDetail.tsx` com edição de limites + suspensão
- [ ] `UserManagement.tsx` com tabela + convite + `PermissionMatrix`
- [ ] `ApiKeys.tsx` com 3 tipos de chave + mask + copy + revoke
- [ ] `GlobalConfig.tsx` com modelos + limites + toggles + audit log
- [ ] `UsageBilling.tsx` com tabela por tenant + exportação CSV
- [ ] Header `X-Impersonate-Tenant` enviado quando em impersonation
- [ ] Rotas protegidas por role no React Router
- [ ] Testes Playwright para fluxos críticos do admin

---

## Checklist de testes Playwright a atualizar

Os testes existentes precisam ser atualizados pois os seletores mudaram:

```ts
// ANTES (Web Component)
const wc = page.locator('archflow-designer')

// DEPOIS (React Flow)
const canvas = page.locator('.react-flow')
const nodes  = page.locator('.react-flow__node')
const edges  = page.locator('.react-flow__edge')

// Verificar que o canvas renderizou
await expect(canvas).toBeVisible()

// Verificar que um nó está no canvas
await expect(nodes.first()).toBeVisible()

// Drag de nó da palette para o canvas
const paletteItem = page.locator('[data-testid="palette-agent"]')
const canvasBbox  = await canvas.boundingBox()
await paletteItem.dragTo(canvas, {
  targetPosition: { x: canvasBbox!.width / 2, y: canvasBbox!.height / 2 }
})
```

---

## Perguntas abertas

1. **Auto-save:** ativar auto-save com debounce de 3s ou manter save manual?
   → Recomendação: manter manual com "Saved Xs ago" no topbar.

2. **Undo/redo:** usar `useUndoable` (lib recomendada pelo xyflow) ou implementar
   manual com snapshot de nodes/edges no Zustand?
   → Recomendação: `useUndoable` — menos código, bem testada.

3. **Persistência de posição dos nós:** o backend já armazena `position: { x, y }`?
   → Verificar no schema do workflow. Se não, adicionar ao `WorkflowStep`.

4. **SSE ou polling:** o backend já tem SSE para eventos de execução?
   → Se não, polling a cada 500ms como fallback temporário.
