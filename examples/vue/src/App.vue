<script setup>
import { ref } from 'vue';

const message = ref('');
const response = ref('');
const loading = ref(false);

const sendMessage = async () => {
  if (!message.value.trim()) return;

  loading.value = true;
  response.value = '';

  try {
    const res = await fetch('/api/agents/customer-service/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: message.value }),
    });

    const data = await res.json();
    response.value = data.response;
  } catch (error) {
    response.value = 'Erro: ' + error.message;
  } finally {
    loading.value = false;
  }
};

const executeWorkflow = async () => {
  try {
    const res = await fetch('/api/workflows/customer-support/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Preciso de ajuda técnica' }),
    });
    const data = await res.json();
    alert(JSON.stringify(data, null, 2));
  } catch (error) {
    alert('Erro: ' + error.message);
  }
};
</script>

<template>
  <div class="app">
    <header class="app-header">
      <h1>archflow + Vue</h1>
      <p>Exemplo de integração do Web Component archflow com Vue 3</p>
    </header>

    <main class="app-main">
      <!-- Workflow Designer -->
      <section class="section">
        <h2>Workflow Designer</h2>
        <div class="designer-container">
          <archflow-designer
            workflow-id="customer-support"
            api-base="http://localhost:8080/api"
            theme="dark"
          />
        </div>
      </section>

      <!-- Chat -->
      <section class="section">
        <h2>Chat com Agente</h2>
        <div class="chat-container">
          <div class="chat-messages">
            <div v-if="response" class="message assistant">
              <div class="message-content">{{ response }}</div>
            </div>
            <div v-if="loading && !response" class="message assistant">
              <div class="message-content typing">Digitando...</div>
            </div>
          </div>

          <div class="chat-input">
            <input
              v-model="message"
              @keyup.enter="sendMessage"
              type="text"
              placeholder="Digite sua mensagem..."
              :disabled="loading"
            />
            <button
              @click="sendMessage"
              :disabled="loading || !message.trim()"
            >
              Enviar
            </button>
          </div>
        </div>
      </section>

      <!-- Workflow API -->
      <section class="section">
        <h2>Workflow API</h2>
        <button @click="executeWorkflow">
          Executar Workflow
        </button>
      </section>
    </main>

    <footer class="app-footer">
      <p>
        Powered by <a href="https://archflow.dev" target="_blank">archflow</a>
      </p>
    </footer>
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  background: linear-gradient(135deg, #8B5CF6 0%, #3B82F6 100%);
  color: white;
  padding: 2rem;
  text-align: center;
}

.app-header h1 {
  margin: 0 0 0.5rem 0;
  font-size: 2rem;
}

.app-header p {
  margin: 0;
  opacity: 0.9;
}

.app-main {
  flex: 1;
  padding: 2rem;
  max-width: 1200px;
  margin: 0 auto;
  width: 100%;
}

.section {
  margin-bottom: 3rem;
}

.section h2 {
  margin-bottom: 1rem;
  color: #1e293b;
}

.designer-container {
  border: 1px solid #e2e8f0;
  border-radius: 0.5rem;
  overflow: hidden;
  background: #0f172a;
  min-height: 400px;
}

.chat-container {
  border: 1px solid #e2e8f0;
  border-radius: 0.5rem;
  overflow: hidden;
}

.chat-messages {
  min-height: 200px;
  max-height: 400px;
  overflow-y: auto;
  padding: 1rem;
  background: #f8fafc;
}

.message {
  margin-bottom: 1rem;
  display: flex;
}

.message.assistant .message-content {
  background: #3b82f6;
  color: white;
  border-radius: 0.5rem 0.5rem 0.5rem 0;
  padding: 0.75rem 1rem;
  max-width: 70%;
}

.message-content.typing {
  opacity: 0.7;
}

.chat-input {
  display: flex;
  gap: 0.5rem;
  padding: 1rem;
  background: white;
  border-top: 1px solid #e2e8f0;
}

.chat-input input {
  flex: 1;
  padding: 0.75rem;
  border: 1px solid #e2e8f0;
  border-radius: 0.375rem;
  font-size: 1rem;
}

.chat-input button {
  padding: 0.75rem 1.5rem;
  background: #3b82f6;
  color: white;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.2s;
}

.chat-input button:hover:not(:disabled) {
  background: #2563eb;
}

.chat-input button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.section button {
  padding: 0.75rem 1.5rem;
  background: #3b82f6;
  color: white;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  font-weight: 600;
}

.app-footer {
  padding: 1rem;
  text-align: center;
  color: #64748b;
  border-top: 1px solid #e2e8f0;
}

.app-footer a {
  color: #3b82f6;
  text-decoration: none;
}

.app-footer a:hover {
  text-decoration: underline;
}
</style>
