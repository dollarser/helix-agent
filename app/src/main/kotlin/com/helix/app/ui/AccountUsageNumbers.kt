package com.helix.app.ui

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/** Readable amounts mark rounded values; calculations retain server-derived precision. */
internal fun accountUsageAmount(
    value: BigDecimal,
    locale: Locale = Locale.getDefault(),
): String {
    val rounded = value.setScale(2, RoundingMode.HALF_UP)
    val formatted =
        NumberFormat
            .getNumberInstance(locale)
            .apply {
                maximumFractionDigits = 2
                minimumFractionDigits = 0
            }.format(rounded)
    return if (rounded.compareTo(value) == 0) formatted else "≈ $formatted"
}
