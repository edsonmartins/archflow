/**
 * Test setup for Web Component tests
 */

import { expect, afterEach } from 'vitest';

// Cleanup after each test
afterEach(() => {
  // Testes com `@vitest-environment node` (ex.: config do build) não têm document.
  if (typeof document !== 'undefined') document.body.innerHTML = '';
});

// Extend Vitest expect with custom matchers if needed
expect.extend({});
