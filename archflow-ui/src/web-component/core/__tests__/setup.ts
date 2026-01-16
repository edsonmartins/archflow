/**
 * Test setup for Web Component tests
 */

import { expect, afterEach } from 'vitest';
import { cleanup } from '@testing-library/dom';

// Cleanup after each test
afterEach(() => {
  cleanup();
});

// Extend Vitest expect with custom matchers if needed
expect.extend({});
