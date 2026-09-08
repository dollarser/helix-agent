package com.helix.app.eval

import com.helix.app.AppContainer
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import java.util.concurrent.atomic.AtomicBoolean

/** Test APK only: execute the production action, then hold its result before Dispatcher settlement. */
internal class ActionResultHold {
    private val completed = AtomicBoolean(false)
    val reached: Boolean get() = completed.get()

    @Suppress("UNCHECKED_CAST")
    fun install(
        container: AppContainer,
        tool: String,
    ) {
        val registry = container.toolPipeline.implementations
        val descriptor = requireNotNull(container.toolPipeline.resolveLatest(tool))
        val original = registry.resolve(descriptor.name, descriptor.version)
        val field = registry.javaClass.getDeclaredField("byNameVersion").apply { isAccessible = true }
        val entries = field.get(registry) as MutableMap<Any, ToolExecutor>
        entries[descriptor.name to descriptor.version] =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    val result = original.execute(call)
                    check(result is ToolExecutorResult.Completed)
                    completed.set(true)
                    Thread.sleep(30000)
                    error("Host missed the action result boundary")
                }
            }
    }
}
