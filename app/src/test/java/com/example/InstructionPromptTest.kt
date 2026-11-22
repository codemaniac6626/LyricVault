package com.example

import com.example.ai.AiMatchingService
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure instruction-resolution rules: which instruction steers a song's
 * matches, and how it is folded into the embedded query.
 */
class InstructionPromptTest {

    // ---- resolveInstruction ----

    @Test
    fun `per-song instruction wins over the global one`() {
        assertEquals(
            "Match by mood",
            AiMatchingService.resolveInstruction("Match by mood", "Prefer recent ideas")
        )
    }

    @Test
    fun `blank per-song instruction falls back to the global one`() {
        assertEquals(
            "Prefer recent ideas",
            AiMatchingService.resolveInstruction("", "Prefer recent ideas")
        )
    }

    @Test
    fun `null per-song instruction falls back to the global one`() {
        assertEquals(
            "Prefer recent ideas",
            AiMatchingService.resolveInstruction(null, "Prefer recent ideas")
        )
    }

    @Test
    fun `whitespace-only per-song instruction falls back to the global one`() {
        assertEquals(
            "Prefer recent ideas",
            AiMatchingService.resolveInstruction("   ", "Prefer recent ideas")
        )
    }

    @Test
    fun `both blank gives no instruction`() {
        assertEquals("", AiMatchingService.resolveInstruction(null, ""))
        assertEquals("", AiMatchingService.resolveInstruction("", "  "))
    }

    @Test
    fun `resolved instruction is trimmed`() {
        assertEquals(
            "Match by mood",
            AiMatchingService.resolveInstruction("  Match by mood  ", "")
        )
    }

    // ---- instructionQuery ----

    @Test
    fun `blank instruction leaves the query untouched`() {
        assertEquals("late night drive", AiMatchingService.instructionQuery("late night drive", ""))
        assertEquals("late night drive", AiMatchingService.instructionQuery("late night drive", "  "))
    }

    @Test
    fun `instruction is folded in front of the query`() {
        assertEquals(
            "Instruction: Match by mood\n\nlate night drive",
            AiMatchingService.instructionQuery("late night drive", "Match by mood")
        )
    }

    @Test
    fun `instruction is trimmed inside the composed query`() {
        assertEquals(
            "Instruction: Match by mood\n\nlate night drive",
            AiMatchingService.instructionQuery("late night drive", "  Match by mood  ")
        )
    }
}
