# Taxonomia de agentes de IA para o ArchFlow: guia arquitetural completo

O mercado de agentes de IA consolidou em 2024-2025 uma taxonomia clara que combina **14 padrões arquiteturais**, **7 primitivas de design**, **10 categorias funcionais de agentes** e **12+ frameworks maduros** — todos convergindo para um princípio validado pela Anthropic, OpenAI e Google: começar pelo mais simples e escalar complexidade apenas quando necessário. O ArchFlow, como framework Java para B2B de distribuição alimentícia, deve implementar uma progressão de complexidade — do LLM aumentado básico ao sistema multi-agente com orquestrador — cobrindo as primitivas fundamentais (memória, tools/MCP, handoff, guardrails, state management e observabilidade) que todo framework maduro oferece. O mercado de agentes cresceu de ~$5,25B em 2024 para ~$7,8B em 2025, com projeção de $50B até 2030. Frameworks Java como LangChain4j (v1.0, 10.2K stars) e Spring AI (1.0 GA) eliminaram a "taxa Python" para equipes enterprise, e o LangGraph4j trouxe orquestração baseada em grafos de estado para a JVM.

---

## Padrões arquiteturais: de prompts simples a busca em árvore

A Anthropic estabeleceu em dezembro de 2024 a distinção fundamental entre **workflows** (orquestração predefinida com código) e **agents** (processos dirigidos pelo LLM). Essa distinção organiza os 14 padrões validados em uma escala de complexidade crescente. Andrew Ng complementou com quatro meta-padrões (Reflection, Tool Use, Planning, Multi-Agent), e Lilian Weng consolidou a decomposição canônica em Planning + Memory + Tool Use.

**LLM Aumentado (Augmented LLM)** é o bloco fundamental: um único LLM com retrieval, tools e memória. Resolve muitos casos sem necessidade de loops agenticos. Complexidade mínima, custo baixo, uma única chamada de LLM. Todo framework implementa nativamente — é o ponto de partida obrigatório para o ArchFlow.

**Chain-of-Thought (CoT)** adiciona raciocínio passo-a-passo via prompting. **CoT-SC** (Self-Consistency) estende isso com múltiplos caminhos de raciocínio e votação majoritária, alcançando **+17,9% no GSM8K** com PaLM-540B. Modelos de raciocínio modernos (o1, Claude extended thinking, Gemini thinking) já incorporam CoT nativamente.

**ReAct** (Reason + Act) é o padrão agentico padrão: loop de Thought→Action→Observation onde o LLM intercala raciocínio com uso de ferramentas. Implementado como default em LangChain, LangGraph, CrewAI, AutoGen e todos os frameworks relevantes. É adaptativo e flexível, mas token-intensivo e serial. O ArchFlow deve implementar ReAct como seu padrão agentico primário.

**Plan-and-Execute** separa planejamento de execução: um Planner (modelo potente) gera um plano multi-step, um Executor (modelo menor/mais barato) executa cada passo, e um Replanner ajusta conforme necessário. Ideal para tarefas complexas onde o plano pode ser antecipado. Mais eficiente em custo que ReAct para tarefas longas. LangGraph tem implementação dedicada.

**ReWOO** (Reasoning Without Observation) planeja todas as chamadas de ferramentas antecipadamente em uma única passagem, usando placeholders (#E1, #E2) para resultados futuros. Apenas 2 chamadas de LLM independentemente do número de ferramentas — **redução de 82%** versus ReAct para 10 ferramentas. Ideal para workflows previsíveis como pipelines de coleta de dados estruturados. Excelente candidato para o ArchFlow em cenários de processamento batch de pedidos.

**Reflexion** adiciona memória dinâmica e autocrítica: após cada tentativa, o agente gera uma reflexão verbal sobre o que falhou, armazena na memória de trabalho (até 3 reflexões), e usa essas reflexões como contexto na próxima tentativa. Requer sinal claro de sucesso/falha. **LATS** (Language Agent Tree Search) combina MCTS com agentes LLM, alcançando **92,7% pass@1 no HumanEval** — mas com custo computacional muito alto. **Tree of Thought** explora múltiplas possibilidades em cada passo com backtracking. Ambos são padrões avançados que o ArchFlow pode oferecer como extensões opcionais.

Os **padrões de workflow da Anthropic** formam uma progressão prática: **Prompt Chaining** (sequência fixa de chamadas LLM com gates de validação), **Routing** (classificação + dispatch para handlers especializados), **Parallelização** (sectioning para subtarefas independentes ou voting para redundância), **Orchestrator-Workers** (decomposição dinâmica em runtime) e **Evaluator-Optimizer** (loop de geração→avaliação→refinamento). O pattern **Supervisor/Worker** — orquestrador central que delega para sub-agentes especializados — é o mais relevante para o ArchFlow em cenários multi-agente, com **90% de melhoria de performance** reportada via exploração paralela. LangGraph Supervisor, CrewAI hierarchical e Google ADK implementam este padrão nativamente.

| Padrão | Complexidade | Chamadas LLM | Adaptabilidade | Melhor Para |
|--------|-------------|-------------|---------------|-------------|
| LLM Aumentado | ⭐ | 1 | Nenhuma | Tarefas simples, RAG básico |
| ReAct | ⭐⭐ | Por step | Alta | Tool use geral, o padrão default |
| Routing | ⭐⭐ | 1 + handler | Baixa | Dispatch multi-domínio (SAC) |
| Plan-and-Execute | ⭐⭐⭐ | 2 + por step | Média | Tarefas multi-step complexas |
| ReWOO | ⭐⭐⭐ | 2 total | Baixa | Workflows previsíveis, batch |
| Supervisor/Worker | ⭐⭐⭐ | Por delegação | Média | Multi-domínio, multi-agente |
| Reflexion | ⭐⭐⭐ | Por episódio × tentativas | Alta entre episódios | Tarefas com sinal de sucesso claro |
| LATS | ⭐⭐⭐⭐⭐ | Muitas (MCTS) | Muito alta | Decisões de alto risco, benchmarks |

---

## Sete primitivas de design que todo framework implementa

A Anthropic define o **LLM aumentado** como bloco fundamental composto por três capacidades: retrieval, tools e memory. A OpenAI complementa com guardrails e orchestration. Juntas, essas capacidades se desdobram em sete primitivas que o ArchFlow deve implementar.

### Memória: quatro camadas inspiradas na cognição

**Memória de curto prazo (working memory)** é o contexto da conversa dentro de uma sessão. No LangGraph, implementada como estado thread-scoped via checkpointers (InMemorySaver, PostgresSaver). No LangChain4j, via interface `ChatMemory`. No OpenAI Agents SDK, Sessions gerenciam o histórico automaticamente. O ArchFlow deve oferecer `ChatMemory` com persistência configurável (in-memory para dev, PostgreSQL para produção).

**Memória episódica** armazena registros de interações passadas com timestamps, ações e resultados. No CrewAI, implementada com ChromaDB + RAG com scoring composto (similaridade semântica + recência + importância). No LangGraph, o `Store` interface permite memória cross-thread com namespacing por tuplas como `(user_id, "memories")`. Essencial para o ArchFlow: um agente de SAC precisa lembrar interações anteriores do mesmo cliente.

**Memória semântica** é a base de conhecimento factual — implementada via vector stores + RAG. LangChain4j oferece `ContentRetriever` com integração para **30+ embedding stores**. Spring AI 1.0 GA inclui framework ETL para ingestão de documentos. Para o ArchFlow em distribuição alimentícia, a memória semântica armazena catálogos de produtos, políticas de preço, regulamentações ANVISA e especificações de cold chain.

**Memória procedural** captura conhecimento sobre como executar tarefas — padrões de uso de ferramentas e workflows aprendidos. O CrewAI implementa via classe `Memory` unificada com inferência automática de escopo/categoria/importância. Menos formalmente suportada em outros frameworks; tipicamente implementada via few-shot examples ou system prompts.

### Tools e Function Calling: o padrão MCP

Tools permitem que agentes interajam com sistemas externos. A definição padrão usa **JSON Schema** (nome, descrição, parâmetros). No LangChain4j, tools são definidas via `@Tool("description")` em métodos Java — abordagem idiomática. No Spring AI, configuração via Beans. Execução pode ser síncrona, assíncrona ou paralela.

O **Model Context Protocol (MCP)**, introduzido pela Anthropic em novembro de 2024 e agora sob a Linux Foundation, é o padrão emergente para conexão universal entre LLMs e ferramentas externas. Arquitetura client-server usando JSON-RPC 2.0, expondo Resources (dados), Tools (ações) e Prompts (templates). **Resolve o problema N×M** de integrações customizadas. OpenAI adotou MCP em março de 2025; SDKs disponíveis em Python, TypeScript, C#, **Java**, Go e Ruby. LangChain4j tem suporte MCP first-class desde v1.0. O ArchFlow deve implementar MCP como protocolo primário de integração de ferramentas.

A Anthropic recomenda princípios práticos: **uma responsabilidade por ferramenta**, preferir `search_contacts` sobre `list_contacts`, consolidar operações multi-step em ferramentas únicas, e usar namespacing (ex: `erp_consultar_estoque`, `logistica_otimizar_rota`).

### Handoff, routing e guardrails

**Handoff** é a transferência de controle entre agentes. No OpenAI Agents SDK, handoffs são representados como tools para o LLM (ex: `transfer_to_refund_agent`), com `input_filter` controlando qual histórico o agente receptor vê. No LangGraph, o objeto `Command(goto="target_agent", update={state})` combina controle de fluxo e atualização de estado. Dois padrões: **Manager** (agentes como tools de um agente central) versus **Handoffs** (transferência descentralizada peer-to-peer).

**Routing** direciona requests ao handler mais apropriado. Três abordagens: rule-based (keywords, regex — rápido mas frágil), **LLM-based** (classificador com structured output — flexível mas adiciona latência), e **semantic** (embeddings de queries comparados a rotas predefinidas via similaridade de cosseno — rápido, sem chamada LLM). A recomendação é routing híbrido: semântico para categorias estáveis, LLM para ambiguidades.

**Guardrails** são redes de segurança programáticas. O OpenAI Agents SDK oferece input guardrails (paralelos com o agente), output guardrails (blocking) e tool guardrails. O **Guardrails AI** framework tem 100+ validadores pré-construídos (ToxicLanguage, DetectPII, ProvenanceLLM). O **NeMo Guardrails** da NVIDIA usa Colang (DSL) com cinco tipos de rails e modelos GPU-acelerados. Para o ArchFlow, guardrails são críticos: validação de valores de pedidos, limites de crédito, conformidade fiscal NF-e.

### State management e observabilidade

O modelo de **state graph do LangGraph** define estado como TypedDict com reducers que controlam como updates são mesclados (overwrite padrão ou append via `Annotated[list, add_messages]`). Checkpointers salvam snapshots a cada super-step, habilitando **human-in-the-loop** (pausa → revisão humana → resumo), **time travel** (replay de execuções anteriores) e **fork** (explorar trajetórias alternativas). O LangGraph4j espelha essa arquitetura com savers para MySQL, Oracle e PostgreSQL. O ArchFlow deve adotar um modelo de estado similar, tipado e com checkpointing.

Para **observabilidade**, três plataformas lideram: **LangSmith** (integração profunda com LangChain, ~1-2% de overhead), **Langfuse** (open-source MIT, OpenTelemetry-compliant, 6M+ installs/mês, ~15% overhead), e **Arize Phoenix** (open-source, container único Docker, todos os recursos gratuitos). O ArchFlow deve integrar via OpenTelemetry para portabilidade, monitorando traces, custos por interação, latência por step, e qualidade de outputs.

---

## Dez categorias funcionais de agentes validadas pelo mercado

### Agentes de pesquisa e RAG (Deep Research)

Agentes que decompõem queries complexas em planos de pesquisa multi-step, executam dezenas de buscas, avaliam fontes e sintetizam relatórios com citações. **OpenAI Deep Research** (fev 2025) usa o3 com RLKF e alcança 67,36% no GAIA. **Perplexity Deep Research** paraleliza com DeepSeek R1 + framework TTC proprietário, citando 100-300 fontes em 2-4 minutos. **Anthropic Claude Deep Research** usa arquitetura multi-agente com orquestrador + sub-agentes paralelos. Maturidade: **produção**. Para o ArchFlow, um agente de pesquisa pode analisar tendências de mercado e regulamentações ANVISA usando RAG sobre bases documentais.

### Agentes conversacionais: SAC e vendas

A categoria mais madura e de maior tração. **Intercom Fin** processou 36M+ conversações com **65% de taxa de resolução** a $0,99/resolução. **Salesforce Agentforce** fechou 18.500+ deals com $100M+ em savings anualizados. **Sierra** atingiu valuation de $10B em setembro de 2025. A arquitetura padrão: classificação de intent → retrieval (RAG) → engine de workflow/policy → execução de ações (API calls) → geração de resposta → decisão de escalação. Duas em três empresas pesquisadas usam ou planejam usar agentes de IA em suporte. Para distribuição alimentícia: agentes de SAC que consultam status de pedidos, verificam disponibilidade de estoque com prazos de validade, processam devoluções e escalam para humanos quando necessário.

### Agentes de código e engenharia

**Claude Code** atingiu $1B em ARR em 6 meses, operando com apenas 4 ferramentas core. **GitHub Copilot** é usado por 85% dos desenvolvedores. **Devin** (Cognition) é totalmente autônomo — o Nubank usou para refatorar milhões de linhas com **12x de eficiência**. No **SWE-bench Verified**, os melhores agentes alcançam ~74-78%, mas no SWE-bench Pro (mais realista) caem para apenas **~23%**. Estudo METR (julho 2025): desenvolvedores experientes com AI levaram **19% mais tempo** apesar de acreditarem ser 20% mais rápidos. Agentes de code review (estilo PullWise) analisam PRs, detectam bugs e sugerem melhorias — padrão mais maduro que codificação autônoma.

### Agentes de automação de processos (RPA Cognitivo)

Evolução do RPA scriptado para agentes que usam visão computacional para navegar qualquer UI. **Anthropic Computer Use** passou de <15% no OSWorld (2024) para **>72%** (início 2026). **UiPath** pivotou para "Agentic Automation" com 75.000+ execuções de agentes desde janeiro 2025, integrando LangChain e Anthropic. O ArchFlow pode oferecer conectores para automação de processos em ERPs legados sem API.

### Agentes verticais B2B/ERP

**SAP Joule** oferece 15+ agentes especializados com 400+ use cases de IA. O Cash Management Agent economiza **70% do tempo** de posicionamento de caixa manual. O Joule Studio (GA dezembro 2025) permite criação no-code de agentes customizados. **Salesforce Agentforce** reporta reduções de tempo de reporting de **99%** (15 dias para 35 minutos) em financial services. **ServiceNow AI Agents** automatiza workflows cross-funcionais. Para o ArchFlow: agentes verticais que integram com SAP/TOTVS via MCP, processam NF-e, calculam ICMS/PIS/COFINS, e gerenciam FEFO (First Expired First Out) para perecíveis.

### Agentes de dados, monitoramento e multi-agente

**Agentes de análise de dados** como Julius AI conectam a PostgreSQL/BigQuery/Snowflake e geram SQL a partir de linguagem natural. Snowflake Cortex com Anthropic alcança **>90% de acurácia em text-to-SQL**. **Agentes de monitoramento** usam LLMs para triagem de alertas e análise de causa raiz — Microsoft Copilot for Security alcança F1 de 0,87 para triagem de incidentes. **Sistemas multi-agente** (MAS) como ChatDev e MetaGPT simulam equipes com roles definidos, mas a tendência de 2025 é que **arquiteturas mais simples vencem em produção**: Claude Code roda com 4 ferramentas, Manus reconstruiu seu framework 5 vezes — cada iteração ficou mais simples. Single-agent systems detêm **59,24%** do mercado por receita.

---

## Doze frameworks comparados: o ecossistema atual

### LangChain/LangGraph domina em complexidade e controle

LangGraph (**46K stars**, v1.0 late 2025) modela workflows como grafos de estado dirigidos com ciclos, checkpointing e human-in-the-loop. Usado em produção por Klarna, Replit, LinkedIn e Uber. Oferece o controle mais fino sobre fluxo de execução, com persistência durável e a melhor observabilidade via LangSmith. Porém, tem curva de aprendizado íngreme — workflows simples que CrewAI faz em 20 linhas requerem 50+ linhas.

**CrewAI** (44.6K stars, $18M Series A, 60% do Fortune 500) usa modelo role-based com crews e tasks — metáfora de equipe humana. Tempo de prototipagem de **2 semanas vs 2 meses** para LangGraph em workflows comparáveis. Duas camadas: Crews (colaboração dinâmica) + Flows (orquestração determinística). Limitação: menos controle granular para lógica condicional complexa.

**AutoGen** (38K stars) da Microsoft usa arquitetura conversacional event-driven com GroupChat. Merging com Semantic Kernel no "Microsoft Agent Framework" (GA Q1 2026). Forte para debate, brainstorming e raciocínio iterativo. **OpenAI Agents SDK** (15K+ stars, março 2025) é minimalista com 4 primitivas (Agents, Handoffs, Guardrails, Tracing) — agente funcional em poucas linhas, mas sem persistência ou orquestração complexa built-in.

### O ecossistema Java amadureceu em 2025

**LangChain4j** (7K+ stars, v1.0-beta1 fev 2025) é a escolha primária para Java: `AiServices` declarativos via interfaces, `@Tool` annotations, 30+ embedding stores, 15+ provedores LLM, suporte MCP first-class. Integra com Spring Boot, Quarkus e Jakarta EE. **Spring AI** (4K+ stars, 1.0 GA maio 2025, 1.1 GA novembro 2025) é a escolha para equipes Spring: Advisors API (interceptor chain), auto-configuração, observabilidade via Micrometer. **LangGraph4j** traz grafos de estado com checkpointing (PostgreSQL, MySQL, Oracle) e padrões ReAct e multi-agente para Java.

**Google ADK** (10K+ stars, abril 2025) é o único framework com suporte multi-linguagem completo (Python, **Java 1.0.0**, Go 1.0.0) e suporte nativo ao protocolo A2A. Para o ArchFlow, a combinação **LangChain4j + LangGraph4j** oferece a base técnica mais madura, com Spring AI como alternativa para equipes no ecossistema Spring.

| Framework | Paradigma | Linguagem | Stars | Força Principal | Fraqueza Principal |
|-----------|-----------|-----------|-------|-----------------|-------------------|
| LangGraph | Grafo de estado | Python/JS | 46K | Controle fino + durabilidade | Curva de aprendizado |
| CrewAI | Crews role-based | Python | 44.6K | Velocidade de prototipagem | Menos controle granular |
| AutoGen | Conversacional | Python/.NET | 38K | Debate e raciocínio iterativo | Complexidade em escala |
| OpenAI SDK | Primitivas mínimas | Python/TS | 15K | Simplicidade extrema | Sem persistência built-in |
| LangChain4j | AI Services | **Java** | 7K | @Tool nativo Java, MCP | Beta, agentes menos maduros |
| Spring AI | Advisors/ChatClient | **Java** | 4K | Ecossistema Spring | Orquestração imatura |
| LangGraph4j | Grafo de estado | **Java** | ~1K | Checkpointing JVM | Menor comunidade |
| Google ADK | Workflow agents | Py/Java/Go | 10K | Multi-lang, A2A nativo | Novo, menos battle-tested |
| Bedrock Agents | Managed service | Any (API) | N/A | Infra gerenciada AWS | Vendor lock-in |
| Vertex AI | Managed + ADK | Py/Java/Go | N/A | Agent Engine managed | Lock-in Google Cloud |

---

## Casos de uso setoriais validados em produção

### Distribuição, logística e supply chain

**C.H. Robinson** opera 30+ agentes de IA conectados processando 37M shipments/ano — em setembro de 2025, um único agente capturou **318.000 atualizações de rastreamento de frete** por telefone. Amazon reduziu stockouts em **32%** com agentes de demand forecasting. A Accenture reporta **18% de redução de custo** e **22% de melhoria** em performance de entrega com route optimization agents.

Para distribuição alimentícia no Brasil, os desafios específicos são severos: **22% de taxa de spoilage** para frutas/vegetais, apenas 32% das estradas rurais pavimentadas, custos de energia representando 32% dos custos logísticos totais, e complexidade regulatória da ANVISA (172 temas regulatórios, 34 relacionados a alimentos). O mercado brasileiro de cold chain vale **USD 15 bilhões**, com carne e aves representando 29%. Agentes específicos para o ArchFlow neste setor incluem: compliance ANVISA (monitorar mudanças regulatórias, validar registros de produtos), monitoramento de cold chain (IoT + alertas autônomos), gerenciamento FEFO para perecíveis, otimização de rotas com restrições de temperatura, e processamento de NF-e com cálculo de ICMS/PIS/COFINS.

### Vendas B2B e SAC

**Salesforce Agentforce** atingiu ~$800M ARR com 12.000+ implementações, cobrando $2/conversação. **Sierra** vale $10B (setembro 2025), atendendo 40% do Fortune 50. BCG identifica três formas de venda agentica: augmented (AI equipa vendedor), assisted (AI como parceiro em tempo real), e autonomous (AI processa micro-transações end-to-end). Para distribuição alimentícia B2B: agentes de qualificação de leads que avaliam potencial de novos pontos de venda, agentes de recomendação de mix de produtos baseados em histórico de compras e sazonalidade, e agentes de SAC que processam reclamações sobre qualidade/validade com acesso ao histórico completo do cliente no ERP.

### ERP e back-office

SAP Joule tem 15+ agentes especializados com Joule Studio (GA dezembro 2025) para criação customizada. O Cash Collection Agent resolve disputas financeiras em **segundos** (antes levava horas), trabalhando cross-funcionalmente entre finanças, SAC e operações. **CoPlane** levantou $14M para automação ERP-adjacente de invoices e procurement. **Klarna** gerencia capacidade equivalente a **850 FTEs** com agentes, gerando **$60M+** em savings operacionais. Para o ArchFlow: agentes que processam pedidos de venda a partir de inputs não-estruturados (WhatsApp, email), reconciliam pagamentos, gerenciam limites de crédito, e automatizam emissão de NF-e.

### DevOps e engenharia de software

**Azure SRE Agent** (GA) é um serviço de confiabilidade always-on que conecta a recursos Azure, telemetria e runbooks via MCP, executando RCA e mitigações dentro de guardrails de política. **NeuBird Hawkeye** reporta **90% de redução em MTTR**. Agentes DevOps automatizam 70-80% de tarefas rotineiras (triagem de anomalias, reconhecimento de alertas, análise de logs). Para o ArchFlow: agentes que monitoram a saúde dos serviços de integração com ERPs, detectam anomalias em fluxos de pedidos, e auto-remediam falhas de sincronização.

---

## Recomendações arquiteturais para o ArchFlow

O ArchFlow deve implementar uma **arquitetura em camadas progressivas** que espelha o princípio da Anthropic — complexidade mínima necessária — adaptada ao ecossistema Java e ao domínio de distribuição alimentícia B2B.

**Camada 1 — LLM Aumentado**: Interface `AiService` (inspirada no LangChain4j) com `@Tool` annotations para function calling, `ChatMemory` para contexto de conversa, e `ContentRetriever` para RAG. Cobre o caso mais comum: chatbot de SAC consultando base de conhecimento sobre produtos, preços e políticas. Suporte a MCP como protocolo padrão de integração de tools.

**Camada 2 — Agente Reativo (ReAct Loop)**: Loop while com function calling — o agente raciocina, seleciona ferramentas, observa resultados e itera. Implementar com max iterations, timeout e fallback. Cobre: processamento de pedidos com verificações (estoque, crédito, validade), pesquisa em catálogo com filtros dinâmicos.

**Camada 3 — Workflows Determinísticos**: Grafo de estados (inspirado no LangGraph4j) com `StateGraph`, nós, edges condicionais, checkpointing (PostgreSQL) e human-in-the-loop. Cobre: pipeline de processamento de NF-e (validar→calcular impostos→emitir→registrar), fluxo de aprovação de crédito, routing de tickets de SAC para especialistas.

**Camada 4 — Multi-Agente (Supervisor/Worker)**: Orquestrador que delega para agentes especializados — Agente de Pedidos, Agente Fiscal, Agente Logístico, Agente de Compliance. Handoff com transferência de estado e input filtering. Cobre: processamento end-to-end de um pedido complexo envolvendo verificação de crédito, cálculo fiscal, alocação de estoque FEFO, e agendamento de entrega com restrições de cold chain.

**Primitivas transversais obrigatórias**: guardrails de input/output (validação de valores monetários, limites de autoridade, PII), observabilidade via OpenTelemetry (traces, custos, latência), state management com checkpointing PostgreSQL, e memória em quatro camadas (working, episódica, semântica, procedural).

A taxonomia oficial de tipos de agentes suportados pelo ArchFlow deve incluir, em ordem de prioridade para distribuição alimentícia B2B:

- **ConversationalAgent** — SAC e vendas, routing por intent, memória episódica por cliente
- **WorkerAgent** — execução especializada (fiscal, logístico, estoque, compliance ANVISA)
- **OrchestratorAgent** — supervisor que coordena workers, implementa Plan-and-Execute
- **ResearchAgent** — Deep Research sobre regulamentações, tendências de mercado, RAG multi-step
- **DataAnalysisAgent** — text-to-SQL para dashboards, análise de vendas, previsão de demanda
- **MonitoringAgent** — vigilância contínua de cold chain, alertas de validade, anomalias em pedidos
- **AutomationAgent** — RPA cognitivo para integração com ERPs legados sem API

Essa taxonomia cobre desde o agente único reativo mais simples até sistemas multi-agente complexos, alinhada com o que o mercado validou em 2024-2025 e com as necessidades específicas de distribuição alimentícia no Brasil — perecibilidade, conformidade ANVISA/MAPA, complexidade fiscal brasileira e infraestrutura logística desafiadora.