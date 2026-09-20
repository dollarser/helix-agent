package com.helix.runtime.cli.app

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Shared native presentation; authentication state and actions remain owned by each login page. */
internal object SubscriptionScreen {
    @Suppress("DEPRECATION") // Native WindowInsets accessors also support API 29.
    fun show(
        activity: Activity,
        content: LinearLayout,
        home: Boolean = false,
    ) {
        val density = activity.resources.displayMetrics.density

        fun dp(value: Int) = (value * density).toInt()
        val shell =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(context.getColor(R.color.subscription_background))
                setOnApplyWindowInsetsListener { view, insets ->
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom,
                    )
                    insets
                }
            }
        shell.addView(header(activity, home, ::dp))
        content.setOnApplyWindowInsetsListener(null)
        content.setPadding(dp(16), dp(12), dp(16), dp(24))
        styleContent(content, ::dp)
        shell.addView(
            ScrollView(activity).apply {
                isFillViewport = true
                addView(content)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val night =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val decor = activity.window.decorView
        decor.systemUiVisibility =
            (decor.systemUiVisibility and lightFlags.inv()) or (if (night) 0 else lightFlags)
        activity.setContentView(shell)
        shell.requestApplyInsets()
    }

    private fun header(
        activity: Activity,
        home: Boolean,
        dp: (Int) -> Int,
    ): LinearLayout {
        val header =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(16), 0)
            }
        if (!home) {
            header.addView(
                Button(activity).apply {
                    text = "‹"
                    textSize = 28f
                    contentDescription = activity.getString(R.string.subscription_back)
                    setTextColor(context.getColor(R.color.subscription_primary))
                    background =
                        RippleDrawable(
                            ColorStateList.valueOf(context.getColor(R.color.subscription_surface)),
                            null,
                            shape(context.getColor(R.color.subscription_background), dp(24)),
                        )
                    setOnClickListener { activity.finish() }
                },
                LinearLayout.LayoutParams(dp(48), dp(48)),
            )
        }
        header.addView(
            TextView(activity).apply {
                text = activity.title
                textSize = 20f
                setTextColor(context.getColor(R.color.subscription_text))
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (home) setPadding(dp(12), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).also { it.gravity = Gravity.CENTER_VERTICAL },
        )
        (header.getChildAt(header.childCount - 1) as TextView).gravity = Gravity.CENTER_VERTICAL
        return header
    }

    private fun styleContent(
        content: LinearLayout,
        dp: (Int) -> Int,
    ) {
        for (index in 0 until content.childCount) {
            val view = content.getChildAt(index)
            if (view is TextView) {
                view.textSize = 16f
                view.setTextColor(view.context.getColor(R.color.subscription_text))
                view.setPadding(dp(16), dp(12), dp(16), dp(12))
                view.background = shape(view.context.getColor(R.color.subscription_surface), dp(16))
                if (view is Button) {
                    view.isAllCaps = false
                    view.minHeight = dp(48)
                    view.stateListAnimator = null
                    view.backgroundTintList = null
                    view.background =
                        RippleDrawable(
                            ColorStateList.valueOf(view.context.getColor(R.color.subscription_ripple)),
                            shape(view.context.getColor(R.color.subscription_surface), dp(24)),
                            null,
                        )
                    view.setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                            intArrayOf(
                                view.context.getColor(R.color.subscription_muted),
                                view.context.getColor(R.color.subscription_primary),
                            ),
                        ),
                    )
                } else {
                    view.setTextIsSelectable(true)
                }
                view.layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(12) }
            }
        }
    }

    private fun shape(
        color: Int,
        radius: Int,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }
}
