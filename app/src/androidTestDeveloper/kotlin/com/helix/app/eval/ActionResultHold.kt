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

    fun install(
        container: AppContainer,
        tool: String,
    ) {
        val registry = container.toolPipeline.registry
        val descriptor = requireNotNull(container.toolPipeline.resolveLatest(tool))
        val originalBinding = registry.resolveBinding(descriptor.name, descriptor.version)
        val original = originalBinding.executor
        val wrapped =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    val result = original.execute(call)
                    check(result is ToolExecutorResult.Completed)
                    completed.set(true)
                    Thread.sleep(30000)
                    error("Host missed the action result boundary")
                }
            }
        registry.replaceOwner(
            originalBinding.ref.owner,
            registry
                .snapshot()
                .filter { it.ref.owner == originalBinding.ref.owner }
                .map {
                    if (it.ref == originalBinding.ref) {
                        it.binding.copy(
                            executor = wrapped,
                            implementationRevision = "test-result-hold",
                        )
                    } else {
                        it.binding
                    }
                },
        )
    }
}
