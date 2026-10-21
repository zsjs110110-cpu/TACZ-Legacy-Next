package com.tacz.legacy.common.foundation

import net.minecraftforge.fml.relauncher.Side
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

internal enum class BootstrapStep {
    PRELOAD_CONFIG,
    CORE_CONFIG,
    DEFAULT_PACK_EXPORT,
    PROXY_PRE_INIT,
    PROXY_INIT,
    PROXY_POST_INIT,
    SERVER_COMMANDS,
    CLIENT_RUNTIME_READY,
}

internal object BootstrapDiagnostics {
    private val completedSteps: MutableList<BootstrapStep> = CopyOnWriteArrayList()

    internal fun reset(): Unit {
        completedSteps.clear()
    }

    internal fun record(step: BootstrapStep): Unit {
        completedSteps += step
    }

    internal fun snapshot(): List<BootstrapStep> = Collections.unmodifiableList(ArrayList(completedSteps))
}

internal object FoundationBootstrap {
    internal fun plannedPreInitSteps(side: Side): List<BootstrapStep> {
        val steps = mutableListOf(
            BootstrapStep.PRELOAD_CONFIG,
            BootstrapStep.CORE_CONFIG,
            BootstrapStep.DEFAULT_PACK_EXPORT,
            BootstrapStep.PROXY_PRE_INIT,
        )
        if (side == Side.CLIENT) {
            steps += BootstrapStep.CLIENT_RUNTIME_READY
        }
        return steps
    }
}

internal object TaczDebugState {
    internal var enabled: Boolean = false
}
