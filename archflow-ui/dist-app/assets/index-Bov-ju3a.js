var l=Object.defineProperty;var d=(o,r,e)=>r in o?l(o,r,{enumerable:!0,configurable:!0,writable:!0,value:e}):o[r]=e;var s=(o,r,e)=>d(o,typeof r!="symbol"?r+"":r,e);import{ae as c,af as w,ag as f}from"./index-Cc8Fc-_K.js";import{ah as y,ai as _}from"./index-Cc8Fc-_K.js";const u="https://unpkg.com/@xyflow/react/dist/style.css";class n extends HTMLElement{constructor(){super();s(this,"root",null);s(this,"container",null);s(this,"shadowRoot_");s(this,"_workflow",null);s(this,"_theme","light");s(this,"_readonly",!1);s(this,"_showMinimap",!0);s(this,"_showGrid",!0);s(this,"_isLoading",!1);s(this,"_isExecuting",!1);s(this,"_selectedNodes",[]);this.shadowRoot_=this.attachShadow({mode:"open"})}static get observedAttributes(){return["workflow-id","api-base","theme","readonly","show-minimap","show-grid","width","height"]}static register(){customElements.get("archflow-designer")||customElements.define("archflow-designer",n)}static isRegistered(){return customElements.get("archflow-designer")===n}connectedCallback(){this.injectStyles(),this.mountReact(),this.emit("connected",{});const e=this.workflowId;e&&this.loadWorkflow(e)}disconnectedCallback(){var e;(e=this.root)==null||e.unmount(),this.root=null}attributeChangedCallback(e,t,i){switch(e){case"theme":this._theme=i==="dark"?"dark":"light";break;case"readonly":this._readonly=i!==null&&i!=="false";break;case"show-minimap":this._showMinimap=i!=="false";break;case"show-grid":this._showGrid=i!=="false";break;case"width":case"height":this.container&&this.applyDimensions();break;case"workflow-id":i&&this.isConnected&&this.loadWorkflow(i);break}this.render()}get workflowId(){return this.getAttribute("workflow-id")}set workflowId(e){e===null?this.removeAttribute("workflow-id"):this.setAttribute("workflow-id",e)}get apiBase(){return this.getAttribute("api-base")??"/api"}set apiBase(e){this.setAttribute("api-base",e)}get theme(){return this._theme}set theme(e){this.setAttribute("theme",e)}get readonly(){return this._readonly}set readonly(e){e?this.setAttribute("readonly",""):this.removeAttribute("readonly")}get width(){return this.getAttribute("width")}set width(e){e===null?this.removeAttribute("width"):this.setAttribute("width",e)}get height(){return this.getAttribute("height")}set height(e){e===null?this.removeAttribute("height"):this.setAttribute("height",e)}get selectedNodes(){return[...this._selectedNodes]}get isLoading(){return this._isLoading}get isExecuting(){return this._isExecuting}get workflow(){return this._workflow}setWorkflow(e){this._workflow=e,this.render()}getWorkflow(){return this._workflow}getWorkflowJson(){return JSON.stringify(this._workflow)}selectNodes(e){this._selectedNodes=[...e],this.emit("nodes-selected",{nodeIds:this.selectedNodes})}clearSelection(){this._selectedNodes=[],this.emit("selection-cleared",{})}reset(){this.workflowId=null,this._workflow=null,this._selectedNodes=[],this.render()}async loadWorkflow(e){await this.loadWorkflowById(e)}async saveWorkflow(){this._workflow&&this.emit("workflow-saved",{workflowId:this.workflowId,version:"1.0.0"})}addNode(e){this.dispatchInternal("__add-node",e)}clearCanvas(){this._workflow={steps:[],connections:[]},this.render()}zoomTo(e){this.dispatchInternal("__zoom-to",{level:e})}fitView(){this.dispatchInternal("__fit-view",{})}injectStyles(){const e=document.createElement("link");e.rel="stylesheet",e.href=u,this.shadowRoot_.appendChild(e);const t=document.createElement("style");t.textContent=`
      :host {
        display: block;
        width: ${this.getAttribute("width")??"100%"};
        height: ${this.getAttribute("height")??"600px"};
        font-family: var(--font-sans, system-ui, sans-serif);

        /* Design tokens the canvas components rely on (App.css defines
           these inside the app; an embedding page has neither, so the
           light-theme values are baked in here as :host defaults —
           without them the status pills and edges render unstyled). */
        --blue:  #2563EB; --blue-l:  #EFF6FF;
        --green: #059669; --green-l: #ECFDF5; --green-text: #065F46;
        --amber: #D97706; --amber-l: #FFFBEB; --amber-text: #92400E;
        --red:   #DC2626; --red-l:   #FEF2F2; --red-text:   #991B1B;
        --gray:  #6B7280; --gray-l:  #F9FAFB;
        --border2: rgba(0, 0, 0, 0.13);
        --text3: #6B7280;
        --text4: #9CA3AF;
        --color-background-primary:   #FFFFFF;
        --color-background-secondary: #F8FAFC;
        --color-background-tertiary:  #F1F5F9;
        --color-border-secondary:     rgba(15, 23, 42, 0.12);
        --color-border-tertiary:      rgba(15, 23, 42, 0.08);
        --color-text-primary:         #0F172A;
        --color-text-secondary:       #334155;
        --color-text-tertiary:        #64748B;
      }
      .canvas-root {
        width: 100%;
        height: 100%;
      }
      @keyframes archflow-dash {
        to { stroke-dashoffset: -18; }
      }
      @keyframes pulse {
        0%, 100% { opacity: 1; }
        50%       { opacity: 0.6; }
      }
    `,this.shadowRoot_.appendChild(t)}mountReact(){this.container=document.createElement("div"),this.container.className="canvas-root",this.shadowRoot_.appendChild(this.container),this.root=c.createRoot(this.container),this.render()}render(){this.root&&this.root.render(w.createElement(f,{initialWorkflow:this._workflow,readonly:this._readonly,showMinimap:this._showMinimap,showGrid:this._showGrid,onNodeSelect:(e,t)=>{e?this.emit("node-selected",{nodeId:e,nodeType:t==null?void 0:t.nodeType,componentId:t==null?void 0:t.componentId}):this.emit("selection-cleared",{})},onWorkflowChange:e=>{this._workflow=e,this.emit("workflow-saved",{workflowId:this.getAttribute("workflow-id"),version:"1.0.0"})},onExecutionRequest:()=>{const e=`exec-${Date.now()}`;this.emit("workflow-executed",{executionId:e,status:"RUNNING"})}}))}applyDimensions(){if(!this.container)return;const e=this.getAttribute("width"),t=this.getAttribute("height");e&&(this.container.style.width=e),t&&(this.container.style.height=t)}emit(e,t){this.dispatchEvent(new CustomEvent(e,{detail:t,bubbles:!0,composed:!0}))}dispatchInternal(e,t){this.container&&this.container.dispatchEvent(new CustomEvent(e,{detail:t,bubbles:!0}))}async loadWorkflowById(e){const t=this.apiBase;this._isLoading=!0;try{const i=window.__archflow_token??"",h=await fetch(`${t}/workflows/${e}`,{headers:i?{Authorization:`Bearer ${i}`}:{}});if(!h.ok)return;const a=await h.json();this.setWorkflow(a),this.emit("workflow-loaded",{workflowId:e,workflow:a})}catch{}finally{this._isLoading=!1}}}n.register();function k(o="archflow-designer"){return customElements.get(o)?(console.warn(`[ArchflowDesigner] Custom element '${o}' is already registered.`),!1):(customElements.define(o,n),console.info(`[ArchflowDesigner] Registered as <${o}>`),!0)}export{n as ArchflowDesigner,n as ArchflowDesignerElement,y as NODE_CATEGORIES,_ as PALETTE_NODES,n as default,k as registerArchflowDesigner};
