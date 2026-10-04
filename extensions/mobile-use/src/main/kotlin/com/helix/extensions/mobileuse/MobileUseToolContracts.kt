package com.helix.extensions.mobileuse

import com.helix.tools.framework.ToolDescriptor

/** Routing facts only. The dispatcher still verifies the live registered binding and permission. */
internal class MobileUseToolContracts(
    descriptors: List<ToolDescriptor>,
) {
    private val owned = descriptors.associateBy { it.name.value }

    fun owns(descriptor: ToolDescriptor?): Boolean = descriptor != null && owned[descriptor.name.value] == descriptor

    fun containsName(name: String?): Boolean = name != null && name in owned
}
