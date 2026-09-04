package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * HXA-069: resolves a localized string [resId] whose positional format arguments are
 * ALREADY-localized strings ([argStrings]), absorbing the single vararg spread in one shared
 * place so the many UI call sites stay read-friendly. Pure-JVM models (approval cards,
 * capability chips, egress labels, attachment kinds) carry a string-resource id + args and
 * never a [android.content.Context]; the UI boundary resolves them here.
 *
 * The spread is a one-shot copy of a small (≤3-element) list for a DISCRETE label — not a
 * per-frame or per-chunk hot path — so the SpreadOperator performance note does not apply.
 */
@Composable
@Suppress("SpreadOperator") // discrete UI-label resolve; stringResource's vararg API has no array overload
fun localizedString(
    resId: Int,
    argStrings: List<String>,
): String = stringResource(resId, *argStrings.toTypedArray())
