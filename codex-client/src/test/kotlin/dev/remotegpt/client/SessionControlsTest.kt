package dev.remotegpt.client

import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlinx.serialization.json.*

class SessionControlsTest {
    @Test fun `commands match exact names and retain multiline prompt`() {
        assertEquals(SessionCommand("/plan", "inspect\nthen propose"), sessionCommand("  /plan inspect\nthen propose  "))
        assertEquals(SessionCommand("/goal", ""), sessionCommand("/goal"))
        assertEquals(SessionCommand("/plan", ""), sessionCommand(" /plan  "))
        assertEquals(SessionCommand("/goal", "目标"), sessionCommand(" /GOAL 目标 "))
        listOf("/default", "/default do work", "/planning", "/plan/file", "/home/me", "explain /plan", "```\n/plan\n```", "/goalkeeper").forEach {
            assertNull(sessionCommand(it))
        }
    }
    @Test fun `command suggestions only appear for the first token`() {
        assertEquals(listOf("/plan", "/goal"), sessionCommandSuggestions("/"))
        assertEquals(listOf("/plan"), sessionCommandSuggestions(" /pl"))
        assertEquals(emptyList(), sessionCommandSuggestions("/plan inspect"))
        assertEquals(emptyList(), sessionCommandSuggestions("explain /pl"))
    }
    @Test fun `cleared goals and large counters decode without narrowing`() {
        assertNull(parseThreadGoal(JsonNull))
        val goal = parseThreadGoal(buildJsonObject {
            put("objective", "目标"); put("status", "budgetLimited"); put("tokensUsed", 3000000000L); put("tokenBudget", JsonNull)
        })!!
        assertEquals(3000000000L, goal.tokensUsed)
        assertNull(goal.tokenBudget)
        assertEquals("budgetLimited", goal.status)
    }
}
