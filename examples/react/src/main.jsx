import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import './index.css';

// Declarar tipo para o Web Component
declare global {
  namespace JSX {
    interface IntrinsicElements {
      'archflow-designer': React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & {
        'workflow-id'?: string;
        'api-base'?: string;
        'theme'?: string;
        ref?: React.Ref<HTMLElement>;
      };
      'archflow-chat': React.DetailedHTMLProps<React.HTMLAttributes<HTMLElement>, HTMLElement> & {
        'agent-id'?: string;
        'api-base'?: string;
        'theme'?: string;
        ref?: React.Ref<HTMLElement>;
      };
    }
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
