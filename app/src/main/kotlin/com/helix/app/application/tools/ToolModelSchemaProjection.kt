package com.helix.app.application.tools

import com.helix.core.model.ModelToolSchema
import com.helix.tools.framework.ToolDescriptor

/** One model-facing contract for exposure accounting and request assembly; never an execution binding. */
internal object ToolModelSchemaProjection {
    fun project(descriptor: ToolDescriptor): ModelToolSchema =
        ToolPresentationMetadata.augment(FileToolArguments.modelSchema(descriptor))
}
