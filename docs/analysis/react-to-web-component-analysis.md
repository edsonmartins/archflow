# React → Web Component: Análise de Opções e Riscos

**Data:** 15 de Janeiro de 2026
**Objetivo:** Avaliar opções para converter componentes React em Web Components

---

## 📊 Resumo Executivo

| Opção | Viabilidade | Risco | Manutenção | Recomendação |
|-------|-------------|-------|------------|--------------|
| **React 19 Nativo** | ✅ Alta | 🟢 Baixo | 🔴 Excelente | ✅ **RECOMENDADO** |
| **Preact + preact-custom-element** | ✅ Alta | 🟢 Baixo | 🟠 Boa | ✅ Alternativa |
| **@r2wc/react-to-web-component** | ⚠️ Média | 🟠 Médio | 🔴 Baixa | ⚠️ Última opção |
| **Svelte → WC** | ✅ Alta | 🟢 Baixo | 🟠 Boa | ✅ Se aceitar mudar stack |

---

## 🎉 Opção 1: React 19 Nativo (RECOMENDADO)

### Por que é a melhor opção

**React 19 foi lançado em 5 de dezembro de 2024** com **suporte nativo a Web Components**.

- Fonte: [React v19 Announcement](https://react.dev/blog/2024/12/05/react-19)
- Blog: ["React 19 support for web components"](https://sordyl.dev/blog/react-19-support-for-web-components/)

### Como funciona

```tsx
// No React 19, Web Components funcionam nativamente
import { useEffect } from 'react';

function App() {
  return (
    <div>
      {/* Web Component funciona diretamente! */}
      <archflow-designer
        workflow-id="customer-support"
        api-base="http://localhost:8080"
        theme="dark" />
    </div>
  );
}
```

### Vantagens

- ✅ **Zero overhead** - sem bibliotecas adicionais
- ✅ **Suporte oficial** - mantido pelo time React
- ✅ **TypeScript nativo**
- ✅ **Performance otimizada**

### Limitações Conhecidas

#### 1. Attributes vs Properties (Issue #29037)
- [GitHub Issue](https://github.com/facebook/react/issues/29037)
- React passa **attributes** (strings) ao invés de **properties** (objetos)
- **Solução:** O Web Component deve lidar com isso:

```typescript
class ArchflowDesigner extends HTMLElement {
  //接受 tanto attributes quanto properties
  set workflowId(value: string) {
    this._workflowId = value;
  }

  get workflowId(): string {
    return this._workflowId;
  }

  connectedCallback() {
    // Converter attributes para properties
    const workflowId = this.getAttribute('workflow-id');
    if (workflowId) {
      this.workflowId = workflowId;
    }
  }
}
```

#### 2. Sem Declarative Shadow DOM (Issue #33698)
- [GitHub Issue](https://github.com/facebook/react/issues/33698)
- React 19 **NÃO suporta** Declarative Shadow DOM para SSR
- **Impacto:** Se precisar de SSR, precisa de workaround

### Exemplo Completo

```typescript
// archflow-designer.ts
export class ArchflowDesigner extends HTMLElement {
  private _workflowId: string = '';
  private _apiBase: string = '';
  private _theme: 'light' | 'dark' = 'light';

  // Properties (para uso via JavaScript/React)
  set workflowId(value: string) {
    if (this._workflowId === value) return;
    this._workflowId = value;
    this.render();
  }

  set apiBase(value: string) {
    if (this._apiBase === value) return;
    this._apiBase = value;
    this.render();
  }

  set theme(value: 'light' | 'dark') {
    if (this._theme === value) return;
    this._theme = value;
    this.render();
  }

  // Lifecycle
  connectedCallback() {
    // Ler attributes iniciais
    const workflowId = this.getAttribute('workflow-id');
    const apiBase = this.getAttribute('api-base') || 'http://localhost:8080';
    const theme = this.getAttribute('theme') as 'light' | 'dark' || 'light';

    if (workflowId) this._workflowId = workflowId;
    if (apiBase) this._apiBase = apiBase;
    if (theme) this._theme = theme;

    // Criar Shadow DOM
    this.attachShadow({ mode: 'open' });
    this.render();
  }

  attributeChangedCallback(name: string, oldValue: string, newValue: string) {
    if (oldValue === newValue) return;

    switch (name) {
      case 'workflow-id':
        this._workflowId = newValue || '';
        break;
      case 'api-base':
        this._apiBase = newValue || '';
        break;
      case 'theme':
        this._theme = (newValue || 'light') as 'light' | 'dark';
        break;
    }
    this.render();
  }

  static get observedAttributes() {
    return ['workflow-id', 'api-base', 'theme'];
  }

  private render() {
    if (!this.shadowRoot) return;
    // Renderização do componente
  }
}

// Registro
customElements.define('archflow-designer', ArchflowDesigner);
```

### Conclusão

✅ **React 19 é VIÁVEL** para Web Components.

**Mitigações para os problemas:**
1. **Attributes/Properties:** Implementar ambos no Web Component
2. **SSR:** Não usar SSR ou usar next.js com app router (que suporta client components)

---

## 🚀 Opção 2: Preact + preact-custom-element

### Por que considerar

**Preact tem suporte NATIVO e MADURO para Web Components.**

- [Preact Web Components Guide](https://preactjs.com/guide/v11/web-components/)
- [preact-custom-element](https://preactjs.com/guide/v10/preact-custom-element/)

### Vantagens

- ✅ **API PreactCompat** - código React funciona quase sem mudanças
- ✅ **Bundle 3x menor** que React
- ✅ **Shadow DOM nativo**
- ✅ **Manutenção ativa**

### Como funciona

```tsx
// Com Preact
import { register } from 'preact-custom-element';
import MyDesigner from './MyDesigner';

// Registra como Web Component
register(MyDesigner, 'archflow-designer', [
  'workflowId',
  'apiBase',
  'theme'
]);
```

### Compatibilidade com React

```tsx
// Seu código React continua funcionando
import { h } from 'preact';
import Router from 'preact-router';

// Preact é quase 100% compatível com React
// Apenas muda o import
```

### Quando usar

- ✅ Se quiser bundle menor
- ✅ Se precisar de Shadow DOM robusto
- ✅ Se quiser evitar problemas do React

### Desvantagens

- ⚠️ Time precisa aprender pequenas diferenças de API
- ⚠️ Algumas libs React podem não ser 100% compatíveis

---

## ⚠️ Opção 3: @r2wc/react-to-web-component

### Status de Manutenção

| Aspecto | Status |
|---------|--------|
| Última versão | 2.0.4 (~9 meses atrás) |
| Manutenção | Baixa atividade |
| Security issues | Nenhum reportado |
| Recomendação oficial | Use como última opção |

### Fontes
- [npm package](https://www.npmjs.com/package/@r2wc/react-to-web-component)
- [GitHub repository](https://github.com/bitovi/react-to-web-component)
- [Snyk security report](https://security.snyk.io/package/npm/@r2wc/react-to-web-component)

### Como funciona

```tsx
import defineCustomElement from '@r2wc/react-to-web-component';
import MyDesigner from './MyDesigner';

// Converte React component para Web Component
const ArchflowDesigner = defineCustomElement(MyDesigner, {
  name: 'archflow-designer',
  props: {
    workflowId: 'string',
    apiBase: 'string',
    theme: 'light' | 'dark'
  },
  shadow: true
});

customElements.define('archflow-designer', ArchflowDesigner);
```

### Problemas Conhecidos

1. **Manutenção baixa** - Sem updates recentes
2. **React 19** - Pode não ser compatível com React 19
3. **Bundle overhead** - Inclui React runtime

---

## 🔴 Opção 4: Svelte → Web Component

### Status

- ✅ Tinyflow (referência) usa com sucesso
- ✅ Compilação nativa para Web Component
- ✅ Excelente suporte a Shadow DOM

### Quando considerar

- ✅ Se o time estiver aberto a aprender Svelte
- ✅ Se bundle size for crítico
- ✅ Se performance for prioridade absoluta

### Desvantagem principal

- ❌ Time React precisa aprender nova tecnologia

---

## 🎯 Recomendação Final

### Para o archflow

**Use React 19 Nativo + Web Component Custom Elements**

```typescript
// Arquitetura recomendada
archflow/
├── archflow-ui/
│   ├── packages/
│   │   ├── archflow-component/        # Web Component (TypeScript puro)
│   │   │   ├── src/
│   │   │   │   ├── ArchflowDesigner.ts  # HTMLElement class
│   │   │   │   ├── Canvas.ts             # Canvas interno
│   │   │   │   ├── nodes/                # Nodes
│   │   │   │   └── styles/               # CSS
│   │   │   └── package.json
│   │   │
│   │   └── archflow-react-adapter/  # Adapter React (opcional)
│   │       ├── src/
│   │       │   ├── ArchflowDesigner.tsx  # React wrapper
│   │       │   └── index.ts
│   │       └── package.json
│   │
│   └── examples/
│       ├── react/                    # Exemplo React 19
│       └── vue/                     # Exemplo Vue
```

### Implementação

```typescript
// archflow-component/src/ArchflowDesigner.ts
export class ArchflowDesigner extends HTMLElement {
  // Implementação Web Component pura (sem React)
  // Usa Vanilla TS ou lit-framework

  connectedCallback() {
    this.attachShadow({ mode: 'open' });
    this.render();
  }

  private render() {
    // Renderização otimizada
  }
}

customElements.define('archflow-designer', ArchflowDesigner);
```

### Uso no React 19

```tsx
// Sem wrapper necessário!
<archflow-designer
  workflow-id="customer-support"
  api-base="http://localhost:8080"
  theme="dark" />
```

---

## 📋 Checklist de Validação

Antes de implementar, validar:

- [ ] Testar React 19 com Web Components simples
- [ ] Verificar behavior de attributes vs properties
- [ ] Testar Shadow DOM styling
- [ ] Validar eventos (CustomEvents)
- [ ] Testar com React Strict Mode
- [ ] Verificar performance com 100+ nodes
- [ ] Testar em Chrome, Firefox, Safari, Edge

---

## Fontes

- [React v19 Announcement](https://react.dev/blog/2024/12/05/react-19)
- [React 19 and Web Component Examples](https://frontendmasters.com/blog/react-19-and-web-component-examples/)
- [Preact Web Components Guide](https://preactjs.com/guide/v11/web-components/)
- [@r2wc/react-to-web-component](https://www.npmjs.com/package/@r2wc/react-to-web-component)
- [Shadow DOM Problem](https://itnext.io/the-shadow-dom-problem-why-web-components-still-struggle-1d0ffe67e824)
- [GitHub Issue #29037](https://github.com/facebook/react/issues/29037)
- [GitHub Issue #33698](https://github.com/facebook/react/issues/33698)
