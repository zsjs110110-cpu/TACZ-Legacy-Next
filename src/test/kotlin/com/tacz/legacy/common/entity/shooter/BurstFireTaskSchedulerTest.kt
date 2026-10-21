package com.tacz.legacy.common.entity.shooter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.function.BooleanSupplier

class BurstFireTaskSchedulerTest {
    @After
    fun tearDown() {
        BurstFireTaskScheduler.resetForTests()
    }

    @Test
    fun `scheduler fires immediately and respects burst period`() {
        var now = 1_000L
        BurstFireTaskScheduler.currentTimeProvider = { now }

        val triggerTimes = mutableListOf<Long>()
        BurstFireTaskScheduler.addCycleTask(BooleanSupplier {
            triggerTimes += now
            true
        }, 100L, 3)

        assertEquals(listOf(1_000L), triggerTimes)

        now = 1_099L
        BurstFireTaskScheduler.tick()
        assertEquals(listOf(1_000L), triggerTimes)

        now = 1_100L
        BurstFireTaskScheduler.tick()
        assertEquals(listOf(1_000L, 1_100L), triggerTimes)

        now = 1_200L
        BurstFireTaskScheduler.tick()
        assertEquals(listOf(1_000L, 1_100L, 1_200L), triggerTimes)
    }

    @Test
    fun `scheduler stops when task returns false`() {
        var now = 2_000L
        BurstFireTaskScheduler.currentTimeProvider = { now }

        var callCount = 0
        BurstFireTaskScheduler.addCycleTask(BooleanSupplier {
            callCount += 1
            callCount < 2
        }, 50L, 5)

        assertEquals(1, callCount)

        now = 2_050L
        BurstFireTaskScheduler.tick()
        assertEquals(2, callCount)

        now = 2_200L
        BurstFireTaskScheduler.tick()
        assertEquals(2, callCount)
    }
}