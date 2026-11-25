import { describe, it, expect } from 'vitest'
import { ANNOTATION_COMPONENT_IDS, isAnnotationStep } from '../constants'

describe('anotações do canvas', () => {
  it('a lista vem das categorias "annotation" (a mesma que o CanvasAnnotation do backend)', () => {
    expect([...ANNOTATION_COMPONENT_IDS].sort()).toEqual(['group-frame', 'section-divider', 'sticky-note'])
  })

  it('reconhece pelo componentId ou pelo type, em qualquer caixa', () => {
    expect(isAnnotationStep({ componentId: 'sticky-note' })).toBe(true)
    expect(isAnnotationStep({ type: 'SECTION-DIVIDER' })).toBe(true)
    expect(isAnnotationStep({ componentId: 'decision', type: 'DECISION' })).toBe(false)
    expect(isAnnotationStep({})).toBe(false)
    expect(isAnnotationStep(null)).toBe(false)
  })
})
