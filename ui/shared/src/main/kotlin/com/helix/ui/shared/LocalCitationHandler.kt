package com.helix.ui.shared

import androidx.compose.runtime.staticCompositionLocalOf

/** Delegates citation navigation to the host; rendering never reads or authorizes source content. */
val LocalCitationHandler = staticCompositionLocalOf<((String) -> Unit)?> { null }
