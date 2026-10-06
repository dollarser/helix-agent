package com.helix.app.eval

import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Synthetic test-APK screen; never included in an application APK. */
class AutomationEvaluationActivity : NotificationFixtureActivity() {
    private var clicks = 0
    private var downs = 0
    private var ups = 0
    private var startX = 0f
    private var startY = 0f
    private var stationary = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("modelJourney", false)) modelJourney() else clickFixture()
        if (intent.getBooleanExtra("notificationPrompt", false) && android.os.Build.VERSION.SDK_INT >= 33) {
            getSharedPreferences("capability-fixture", MODE_PRIVATE).edit().putInt("notification", -1).commit()
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 104)
        }
    }

    private fun clickFixture() {
        val recordClicks = intent.getBooleanExtra("recordClicks", false)
        val counter = getSharedPreferences("ui-goal-kill-counter", MODE_PRIVATE)
        if (recordClicks) check(counter.edit().putInt("clicks", 0).commit())
        clicks = 0
        val payment = intent.getBooleanExtra("payment", false)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val result = TextView(this).apply { text = "FIXTURE_UNCHANGED" }
        layout.addView(result)
        val touches = TextView(this)
        val recordTouches = intent.getBooleanExtra("recordTouches", false)
        if (recordTouches) {
            // API36 forces edge-to-edge; keep this independent test-APK button below system/title bars.
            layout.setPadding(0, (160 * resources.displayMetrics.density).toInt(), 0, 0)
            layout.addView(touches)
        }
        layout.addView(
            Button(this).apply {
                id = android.R.id.button1
                text = if (payment) "Confirm payment" else "Fixture click"
                contentDescription = text
                if (recordTouches) {
                    setAllCaps(false)
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            downs++
                            startX = event.x
                            startY = event.y
                            stationary = true
                        }
                        stationary = stationary && event.x == startX && event.y == startY
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            ups++
                            touches.text = "TOUCH:$downs:$ups:${event.eventTime - event.downTime}:$stationary"
                        }
                        false
                    }
                }
                setOnClickListener {
                    clicks++
                    if (recordClicks) check(counter.edit().putInt("clicks", clicks).commit())
                    result.text = "FIXTURE_CLICKED"
                    if (recordClicks) result.contentDescription = "fixture-click-count:$clicks"
                }
            },
        )
        setContentView(layout)
    }

    private fun modelJourney() {
        val outcome = getSharedPreferences("capability-fixture", MODE_PRIVATE)
        check(outcome.edit().clear().commit())
        val completions = intArrayOf(0)
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 120, 24, 96)
            }
        val input =
            android.widget.EditText(this).apply {
                hint = "测试输入"
                contentDescription = "测试输入"
                setSingleLine(true)
            }
        layout.addView(input)
        repeat(24) { index ->
            layout.addView(
                TextView(this).apply {
                    text = "测试条目 ${index + 1}"
                    setPadding(0, 32, 0, 32)
                },
            )
        }
        val result = TextView(this)
        layout.addView(
            Button(this).apply {
                text = "完成测试"
                setOnClickListener {
                    completions[0]++
                    result.text = "TEST_DONE:${input.text}"
                    check(
                        outcome
                            .edit()
                            .putString(
                                "result",
                                "TEST_DONE:${input.text}",
                            ).putInt("clicks", completions[0])
                            .commit(),
                    )
                }
            },
        )
        layout.addView(result)
        setContentView(android.widget.ScrollView(this).apply { addView(layout) })
    }
}
