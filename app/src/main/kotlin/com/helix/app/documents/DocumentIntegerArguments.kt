package com.helix.app.documents

import com.helix.extensions.documents.DocumentException
import com.helix.tools.framework.ToolJsonNumbers
import kotlinx.serialization.json.JsonObject

/** Absence uses the documented default; supplied invalid values never change the requested window. */
internal object DocumentIntegerArguments {
    fun read(
        args: JsonObject,
        key: String,
        default: Int,
    ): Int =
        if (key !in
            args
        ) {
            default
        } else {
            ToolJsonNumbers.intOrNull(args[key]) ?: throw DocumentException("INVALID_ARGUMENT")
        }
}
