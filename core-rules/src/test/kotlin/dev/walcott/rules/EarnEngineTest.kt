package dev.walcott.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class EarnEngineTest {

    private val eduToGames = EarnRule(
        sourceCategoryId = "education",
        targetCategoryId = "games",
        sourceMinutesPerReward = 10,
        rewardMinutes = 5,
        dailyCapMinutes = 30,
    )

    @Test
    fun `full blocks earn the reward`() {
        // 25 min of education -> 2 full blocks -> 10 min of games.
        val earned = EarnEngine.computeEarned(
            listOf(eduToGames),
            mapOf("education" to Duration.ofMinutes(25)),
        )
        assertEquals(Duration.ofMinutes(10), earned["games"])
    }

    @Test
    fun `partial block earns nothing`() {
        val earned = EarnEngine.computeEarned(
            listOf(eduToGames),
            mapOf("education" to Duration.ofMinutes(9)),
        )
        assertTrue(earned.isEmpty())
    }

    @Test
    fun `earning is capped per day`() {
        // 200 min would be 100 min, but the cap is 30.
        val earned = EarnEngine.computeEarned(
            listOf(eduToGames),
            mapOf("education" to Duration.ofMinutes(200)),
        )
        assertEquals(Duration.ofMinutes(30), earned["games"])
    }

    @Test
    fun `multiple rules to the same target accumulate`() {
        val choresToGames = EarnRule("chores", "games", 5, 10, 60)
        val earned = EarnEngine.computeEarned(
            listOf(eduToGames, choresToGames),
            mapOf("education" to Duration.ofMinutes(20), "chores" to Duration.ofMinutes(10)),
        )
        // education: 2 blocks * 5 = 10; chores: 2 blocks * 10 = 20; total 30.
        assertEquals(Duration.ofMinutes(30), earned["games"])
    }

    @Test
    fun `no source usage earns nothing`() {
        assertTrue(EarnEngine.computeEarned(listOf(eduToGames), emptyMap()).isEmpty())
    }

    @Test
    fun `invalid rule (zero block size) is ignored`() {
        val bad = EarnRule("education", "games", 0, 5, 30)
        assertTrue(EarnEngine.computeEarned(listOf(bad), mapOf("education" to Duration.ofMinutes(60))).isEmpty())
    }
}
