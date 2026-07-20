import { useState, useEffect, useRef } from 'react';
import './App.css';

function App() {
  const [message, setMessage] = useState('');
  const [response, setResponse] = useState('');
  const [loading, setLoading] = useState(false);
  const designerRef = useRef(null);

  // Invocar um agente diretamente (gatilho sob demanda).
  // Endpoint real do backend: POST /archflow/agents/{agentId}/invoke
  // A invocação é ASSÍNCRONA — a resposta é um "accepted" com requestId,
  // não a resposta do agente (não há endpoint de chat/stream síncrono).
  const sendMessage = async () => {
    if (!message.trim()) return;

    setLoading(true);
    setResponse('');

    try {
      const res = await fetch('/archflow/agents/customer-service/invoke', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          tenantId: 'default',
          payload: { message },
        }),
      });

      const data = await res.json();
      // data => { requestId, tenantId, agentId, status: "accepted" }
      setResponse(`Invocação aceita (requestId: ${data.requestId}, status: ${data.status})`);
    } catch (error) {
      setResponse('Erro: ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="app">
      <header className="app-header">
        <h1>archflow + React</h1>
        <p>Exemplo de integração do Web Component archflow com React</p>
      </header>

      <main className="app-main">
        {/* Exemplo do Designer */}
        <section className="section">
          <h2>Workflow Designer</h2>
          <div className="designer-container">
            <archflow-designer
              ref={designerRef}
              workflow-id="customer-support"
              api-base="http://localhost:8080/api"
              theme="dark"
            />
          </div>
        </section>

        {/* Exemplo de invocação de agente (assíncrona) */}
        <section className="section">
          <h2>Invocar Agente (assíncrono)</h2>
          <div className="chat-container">
            <div className="chat-messages">
              {response && (
                <div className="message assistant">
                  <div className="message-content">{response}</div>
                </div>
              )}
              {loading && !response && (
                <div className="message assistant">
                  <div className="message-content typing">
                    Digitando...
                  </div>
                </div>
              )}
            </div>

            <div className="chat-input">
              <input
                type="text"
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && sendMessage()}
                placeholder="Digite sua mensagem..."
                disabled={loading}
              />
              <button
                onClick={sendMessage}
                disabled={loading || !message.trim()}
              >
                Enviar
              </button>
            </div>
          </div>
        </section>

        {/* Exemplo de Workflow via API */}
        <section className="section">
          <h2>Workflow API</h2>
          <button
            onClick={async () => {
              const res = await fetch('/api/workflows/customer-support/execute', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ message: 'Preciso de ajuda técnica' }),
              });
              const data = await res.json();
              alert(JSON.stringify(data, null, 2));
            }}
          >
            Executar Workflow
          </button>
        </section>
      </main>

      <footer className="app-footer">
        <p>
          Powered by <a href="https://archflow.dev" target="_blank">archflow</a>
        </p>
      </footer>
    </div>
  );
}

export default App;
