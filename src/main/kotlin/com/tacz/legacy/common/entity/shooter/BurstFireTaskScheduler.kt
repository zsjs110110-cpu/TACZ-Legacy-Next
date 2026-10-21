package com.tacz.legacy.common.entity.shooter

import java.util.function.BooleanSupplier

/**
 * 轻量级循环任务调度器，对齐上游 CycleTaskHelper 的 burst 连发节拍语义：
 * - 注册时立刻执行一次
 * - 后续由服务端 tick 驱动
 * - 周期内若服务器掉 tick，会在下一次 tick 时补执行遗漏周期
 */
internal object BurstFireTaskScheduler {
    @Volatile
    internal var currentTimeProvider: () -> Long = System::currentTimeMillis

    private val tasks: MutableList<CycleTask> = mutableListOf()

    @Synchronized
    internal fun addCycleTask(task: BooleanSupplier, periodMs: Long, cycles: Int) {
        addCycleTask(task, 0L, periodMs, cycles)
    }

    @Synchronized
    internal fun addCycleTask(task: BooleanSupplier, delayMs: Long, periodMs: Long, cycles: Int) {
        if (cycles == 0) {
            return
        }
        val now = currentTimeProvider()
        if (delayMs <= 0) {
            // immediate first execution
            if (!task.asBoolean) {
                return
            }
            val remainingCycles = if (cycles < 0) -1 else cycles - 1
            if (remainingCycles == 0) {
                return
            }
            tasks += CycleTask(
                task = task,
                nextRunAtMs = now + periodMs.coerceAtLeast(1L),
                periodMs = periodMs.coerceAtLeast(1L),
                remainingCycles = remainingCycles,
            )
        } else {
            // delayed first execution
            tasks += CycleTask(
                task = task,
                nextRunAtMs = now + delayMs,
                periodMs = periodMs.coerceAtLeast(1L),
                remainingCycles = cycles,
            )
        }
    }

    @Synchronized
    internal fun tick() {
        if (tasks.isEmpty()) {
            return
        }
        val now = currentTimeProvider()
        val iterator = tasks.iterator()
        while (iterator.hasNext()) {
            val ticker = iterator.next()
            if (!ticker.tick(now)) {
                iterator.remove()
            }
        }
    }

    @Synchronized
    internal fun resetForTests() {
        tasks.clear()
        currentTimeProvider = System::currentTimeMillis
    }

    private class CycleTask(
        private val task: BooleanSupplier,
        private var nextRunAtMs: Long,
        private val periodMs: Long,
        private var remainingCycles: Int,
    ) {
        fun tick(now: Long): Boolean {
            while (now >= nextRunAtMs) {
                if (!task.asBoolean) {
                    return false
                }
                if (remainingCycles > 0) {
                    remainingCycles -= 1
                    if (remainingCycles == 0) {
                        return false
                    }
                }
                nextRunAtMs += periodMs
            }
            return true
        }
    }
}