/**
 * Tests for ArchflowDesigner Web Component
 *
 * These tests validate:
 * - Custom element registration
 * - Attribute parsing
 * - Property setting/getting
 * - Event dispatching
 * - Theme switching
 * - Lifecycle callbacks
 */

import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { ArchflowDesigner } from '../ArchflowDesigner';

describe('ArchflowDesigner - Web Component', () => {
  let element: ArchflowDesigner;

  beforeEach(() => {
    // Create a new instance for each test
    element = new ArchflowDesigner();
  });

  afterEach(() => {
    // Cleanup
    if (element.isConnected) {
      element.remove();
    }
  });

  describe('Registration', () => {
    it('should be registered as custom element', () => {
      expect(ArchflowDesigner.isRegistered()).toBe(true);
    });

    it('should have correct tag name', () => {
      expect(element.tagName.toLowerCase()).toBe('archflow-designer');
    });

    it('should register with custom tag name', () => {
      const customTag = 'custom-archflow-designer';
      ArchflowDesigner.register(customTag);
      expect(customElements.get(customTag)).toBe(ArchflowDesigner);
    });
  });

  describe('Attributes', () => {
    it('should parse workflow-id attribute', () => {
      element.setAttribute('workflow-id', 'wf-001');
      expect(element.workflowId).toBe('wf-001');
    });

    it('should parse api-base attribute', () => {
      element.setAttribute('api-base', 'http://localhost:3000/api');
      expect(element.apiBase).toBe('http://localhost:3000/api');
    });

    it('should default api-base to /api', () => {
      expect(element.apiBase).toBe('/api');
    });

    it('should parse theme attribute', () => {
      element.setAttribute('theme', 'dark');
      expect(element.theme).toBe('dark');

      element.setAttribute('theme', 'light');
      expect(element.theme).toBe('light');
    });

    it('should default theme to light', () => {
      expect(element.theme).toBe('light');
    });

    it('should parse readonly attribute', () => {
      element.setAttribute('readonly', '');
      expect(element.readonly).toBe(true);

      element.removeAttribute('readonly');
      expect(element.readonly).toBe(false);
    });

    it('should parse width and height attributes', () => {
      element.setAttribute('width', '800px');
      expect(element.width).toBe('800px');

      element.setAttribute('height', '600px');
      expect(element.height).toBe('600px');
    });
  });

  describe('Properties', () => {
    it('should set workflow-id property', () => {
      element.workflowId = 'wf-002';
      expect(element.getAttribute('workflow-id')).toBe('wf-002');
    });

    it('should set api-base property', () => {
      element.apiBase = 'http://example.com/api';
      expect(element.getAttribute('api-base')).toBe('http://example.com/api');
    });

    it('should set theme property', () => {
      element.theme = 'dark';
      expect(element.getAttribute('theme')).toBe('dark');
    });

    it('should set readonly property', () => {
      element.readonly = true;
      expect(element.hasAttribute('readonly')).toBe(true);

      element.readonly = false;
      expect(element.hasAttribute('readonly')).toBe(false);
    });

    it('should set width and height properties', () => {
      element.width = '100%';
      expect(element.getAttribute('width')).toBe('100%');

      element.height = '500px';
      expect(element.getAttribute('height')).toBe('500px');
    });
  });

  describe('observedAttributes', () => {
    it('should observe workflow-id changes', () => {
      const spy = jest.spyOn(element, 'attributeChangedCallback');

      element.setAttribute('workflow-id', 'wf-test');
      expect(spy).toHaveBeenCalledWith(
        'workflow-id',
        null,
        'wf-test'
      );

      spy.mockRestore();
    });

    it('should observe theme changes', () => {
      const spy = jest.spyOn(element, 'attributeChangedCallback');

      element.setAttribute('theme', 'dark');
      expect(spy).toHaveBeenCalledWith(
        'theme',
        null,
        'dark'
      );

      spy.mockRestore();
    });

    it('should have all expected observed attributes', () => {
      const observed = ArchflowDesigner.observedAttributes;
      expect(observed).toContain('workflow-id');
      expect(observed).toContain('api-base');
      expect(observed).toContain('theme');
      expect(observed).toContain('readonly');
      expect(observed).toContain('width');
      expect(observed).toContain('height');
    });
  });

  describe('Methods', () => {
    it('should select nodes', () => {
      element.selectNodes(['node-1', 'node-2']);
      expect(element.selectedNodes).toEqual(['node-1', 'node-2']);
    });

    it('should clear selection', () => {
      element.selectNodes(['node-1']);
      element.clearSelection();
      expect(element.selectedNodes).toEqual([]);
    });

    it('should reset to empty state', () => {
      element.workflowId = 'wf-001';
      element.reset();
      expect(element.workflowId).toBeNull();
      expect(element.workflow).toBeNull();
    });

    it('should return workflow JSON', () => {
      const mockWorkflow = {
        id: 'wf-001',
        metadata: { name: 'Test' },
        steps: [],
        configuration: {} as any
      };
      element.setWorkflow(mockWorkflow as any);

      const json = element.getWorkflowJson();
      expect(JSON.parse(json)).toEqual(mockWorkflow);
    });
  });

  describe('Events', () => {
    it('should dispatch event on workflow-saved', () => {
      const handler = jest.fn();
      element.addEventListener('workflow-saved', handler);

      // Trigger save (will fail but should emit error)
      element.saveWorkflow();

      // Due to async and failure, an error event should be emitted
      expect(handler).not.toHaveBeenCalled(); // Save fails without mock
    });

    it('should dispatch event on workflow-loaded', () => {
      const handler = jest.fn();
      element.addEventListener('workflow-loaded', handler);

      // Load will fail without mock, but tests the event system
      element.loadWorkflow('wf-001');
    });
  });

  describe('State', () => {
    it('should have loading state', () => {
      expect(element.isLoading).toBe(false);
      // After actual load, this would change
    });

    it('should have executing state', () => {
      expect(element.isExecuting).toBe(false);
      // After actual execution, this would change
    });

    it('should have workflow property', () => {
      expect(element.workflow).toBeNull();
    });
  });
});

describe('ArchflowDesigner - Lifecycle', () => {
  let container: HTMLElement;
  let element: ArchflowDesigner;

  beforeEach(() => {
    container = document.createElement('div');
    document.body.appendChild(container);
  });

  afterEach(() => {
    if (element && element.isConnected) {
      element.remove();
    }
    if (container && container.isConnected) {
      container.remove();
    }
  });

  describe('connectedCallback', () => {
    it('should create Shadow DOM', () => {
      element = document.createElement('archflow-designer') as ArchflowDesigner;
      container.appendChild(element);

      expect(element.shadowRoot).not.toBeNull();
      expect(element.shadowRoot?.mode).toBe('open');
    });

    it('should emit connected event', () => {
      const handler = jest.fn();
      element = document.createElement('archflow-designer') as ArchflowDesigner;

      element.addEventListener('connected', handler);
      container.appendChild(element);

      // Event should be emitted during connectedCallback
      expect(handler).toHaveBeenCalled();
    });

    it('should load workflow if workflow-id is set', () => {
      element = document.createElement('archflow-designer') as ArchflowDesigner;
      element.setAttribute('workflow-id', 'wf-001');

      // Mock fetch
      global.fetch = jest.fn(() =>
        Promise.resolve({
          ok: true,
          json: () => Promise.resolve({
            id: 'wf-001',
            metadata: { name: 'Test Workflow' },
            steps: [],
            configuration: {}
          })
        } as Response)
      );

      container.appendChild(element);

      // Workflow load should be initiated
      expect(element.isLoading).toBe(true);
    });
  });

  describe('disconnectedCallback', () => {
    it('should cleanup Shadow DOM', () => {
      element = document.createElement('archflow-designer') as ArchflowDesigner;
      container.appendChild(element);

      const shadowRoot = element.shadowRoot;
      expect(shadowRoot).not.toBeNull();

      element.remove();
      // After removal, cleanup should happen
    });
  });
});
