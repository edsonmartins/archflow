package br.com.archflow.agent.pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ReflectionMemory")
class ReflectionMemoryTest {

    @Test
    @DisplayName("should store and retrieve reflections")
    void shouldStoreAndRetrieveReflections() {
        ReflectionMemory memory = new ReflectionMemory(5);

        memory.addReflection("attempt 1", "failed", "Need to use a different approach");
        memory.addReflection("attempt 2", "partial", "Getting closer, fix the edge case");

        assertEquals(2, memory.size());
        assertFalse(memory.isEmpty());

        var reflections = memory.getReflections();
        assertEquals(2, reflections.size());
        assertEquals("attempt 1", reflections.get(0).attempt());
        assertEquals("failed", reflections.get(0).outcome());
        assertEquals("Need to use a different approach", reflections.get(0).reflection());
        assertNotNull(reflections.get(0).timestamp());
    }

    @Test
    @DisplayName("should enforce max reflections limit")
    void shouldEnforceMaxReflectionsLimit() {
        ReflectionMemory memory = new ReflectionMemory(2);

        memory.addReflection("attempt 1", "fail", "reflection 1");
        memory.addReflection("attempt 2", "fail", "reflection 2");
        memory.addReflection("attempt 3", "fail", "reflection 3");

        assertEquals(2, memory.size());
        // Oldest should be evicted
        var reflections = memory.getReflections();
        assertEquals("reflection 2", reflections.get(0).reflection());
        assertEquals("reflection 3", reflections.get(1).reflection());
    }

    @Test
    @DisplayName("should format reflections as context string")
    void shouldFormatReflectionsAsContext() {
        ReflectionMemory memory = new ReflectionMemory(5);

        memory.addReflection("used brute force", "timeout", "Should use dynamic programming");
        memory.addReflection("used DP without memo", "wrong answer", "Need memoization");

        String context = memory.formatAsContext();

        assertTrue(context.startsWith("Previous reflections:"));
        assertTrue(context.contains("used brute force"));
        assertTrue(context.contains("timeout"));
        assertTrue(context.contains("Should use dynamic programming"));
        assertTrue(context.contains("Need memoization"));
    }

    @Test
    @DisplayName("should return empty string when no reflections")
    void shouldReturnEmptyStringWhenNoReflections() {
        ReflectionMemory memory = new ReflectionMemory();

        assertEquals("", memory.formatAsContext());
        assertTrue(memory.isEmpty());
        assertEquals(0, memory.size());
    }

    @Test
    @DisplayName("should clear all reflections")
    void shouldClearAllReflections() {
        ReflectionMemory memory = new ReflectionMemory(5);

        memory.addReflection("attempt 1", "fail", "reflection 1");
        memory.addReflection("attempt 2", "fail", "reflection 2");
        assertEquals(2, memory.size());

        memory.clear();

        assertEquals(0, memory.size());
        assertTrue(memory.isEmpty());
        assertEquals("", memory.formatAsContext());
    }
}
