package com.helix.tools.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Duration

/**
 * Test-only bridge that executes in the application component classloader. Android library
 * instrumentation uses a separate classloader, so reading a Kotlin singleton directly from the
 * test would observe a second instance rather than the one used by [HelixAccessibilityService].
 */
class AutomationTestControlReceiver : BroadcastReceiver() {
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val center = AutomationPermissionCenter(context)
        var snapshot: AutomationSnapshot? = null
        var actionToken: String? = null
        val result =
            when (intent.action) {
                ACTION_PROBE -> {
                    center.serviceState().name
                }

                ACTION_REPLACE_ALLOWLIST -> {
                    center.replaceAllowlist(intent.getStringArrayExtra(EXTRA_PACKAGES).orEmpty().toSet())
                    RESULT_OK
                }

                ACTION_START -> {
                    val ttl =
                        if (intent.hasExtra(EXTRA_TTL_MILLIS)) {
                            Duration.ofMillis(intent.getLongExtra(EXTRA_TTL_MILLIS, 0L))
                        } else {
                            AutomationSessionManager.DEFAULT_TTL
                        }
                    val maxActions =
                        intent.getIntExtra(
                            EXTRA_MAX_ACTIONS,
                            AutomationSessionManager.DEFAULT_MAX_ACTIONS,
                        )
                    center
                        .startSession(
                            intent.getStringArrayExtra(EXTRA_PACKAGES).orEmpty().toSet(),
                            ttl,
                            maxActions,
                        ).status.name
                }

                ACTION_STOP -> {
                    center.stopSession()
                    RESULT_OK
                }

                ACTION_SNAPSHOT -> {
                    center
                        .snapshot()
                        .also { snapshot = it.snapshot }
                        .status.name
                }

                ACTION_FIND -> {
                    val captured = center.snapshot()
                    snapshot = captured.snapshot
                    if (captured.status != AutomationSnapshotStatus.SUCCESS || snapshot == null) {
                        captured.status.name
                    } else {
                        AutomationFinder
                            .find(
                                checkNotNull(snapshot),
                                AutomationFindQuery(
                                    text = intent.getStringExtra(EXTRA_QUERY_TEXT),
                                    contentDescription =
                                        intent.getStringExtra(EXTRA_QUERY_DESCRIPTION),
                                    className = intent.getStringExtra(EXTRA_QUERY_CLASS),
                                    clickable =
                                        intent
                                            .takeIf { it.hasExtra(EXTRA_QUERY_CLICKABLE) }
                                            ?.getBooleanExtra(EXTRA_QUERY_CLICKABLE, false),
                                ),
                            ).also { actionToken = it.nodes.firstOrNull()?.token }
                            .status.name
                    }
                }

                ACTION_NODE -> {
                    val action =
                        runCatching {
                            AutomationNodeAction.valueOf(
                                intent.getStringExtra(EXTRA_NODE_ACTION).orEmpty(),
                            )
                        }.getOrNull()
                    if (action == null) {
                        AutomationActionStatus.INVALID_ARGUMENT.name
                    } else {
                        center
                            .performNodeAction(
                                AutomationNodeActionRequest(
                                    action = action,
                                    token = intent.getStringExtra(EXTRA_TOKEN).orEmpty(),
                                    text = intent.getStringExtra(EXTRA_TEXT),
                                ),
                            ).status.name
                    }
                }

                ACTION_FIND_AND_NODE -> {
                    val action =
                        runCatching {
                            AutomationNodeAction.valueOf(
                                intent.getStringExtra(EXTRA_NODE_ACTION).orEmpty(),
                            )
                        }.getOrNull()
                    val captured = center.snapshot()
                    snapshot = captured.snapshot
                    val token =
                        snapshot
                            ?.let { current ->
                                AutomationFinder.find(
                                    current,
                                    AutomationFindQuery(
                                        text = intent.getStringExtra(EXTRA_QUERY_TEXT),
                                        contentDescription =
                                            intent.getStringExtra(EXTRA_QUERY_DESCRIPTION),
                                    ),
                                )
                            }?.nodes
                            ?.firstOrNull()
                            ?.token
                    when {
                        action == null -> {
                            AutomationActionStatus.INVALID_ARGUMENT.name
                        }

                        captured.status != AutomationSnapshotStatus.SUCCESS -> {
                            captured.status.name
                        }

                        token == null -> {
                            AutomationFindStatus.NOT_FOUND.name
                        }

                        else -> {
                            center
                                .performNodeAction(
                                    AutomationNodeActionRequest(
                                        action,
                                        token,
                                        intent.getStringExtra(EXTRA_TEXT),
                                    ),
                                ).status.name
                        }
                    }
                }

                ACTION_GLOBAL -> {
                    val action =
                        runCatching {
                            AutomationGlobalAction.valueOf(
                                intent.getStringExtra(EXTRA_GLOBAL_ACTION).orEmpty(),
                            )
                        }.getOrNull()
                    action
                        ?.let(center::performGlobalAction)
                        ?.status
                        ?.name
                        ?: AutomationActionStatus.INVALID_ARGUMENT.name
                }

                ACTION_PAUSE_PROBE -> {
                    center.pauseReason()?.name ?: RESULT_NONE
                }

                ACTION_GENERATION_PROBE -> {
                    AutomationServiceController.currentGeneration()?.toString() ?: RESULT_NONE
                }

                ACTION_RESUME -> {
                    center
                        .resumeAfterUserConfirmation(intent.getStringExtra(EXTRA_EXPECTED_PACKAGE).orEmpty())
                        .name
                }

                ACTION_DISABLE_SERVICE -> {
                    center.disableService()
                    RESULT_OK
                }

                else -> {
                    RESULT_UNKNOWN_ACTION
                }
            }
        val active = center.activeSession()
        check(
            context
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_NONCE, intent.getLongExtra(EXTRA_NONCE, -1))
                .putString(KEY_RESULT, result)
                .putBoolean(KEY_ACTIVE, active != null)
                .putString(KEY_SNAPSHOT_PACKAGE, snapshot?.packageName)
                .putInt(KEY_SNAPSHOT_WINDOW, snapshot?.windowId ?: -1)
                .putLong(KEY_SNAPSHOT_GENERATION, snapshot?.generation ?: -1L)
                .putInt(KEY_SNAPSHOT_NODE_COUNT, snapshot?.nodes?.size ?: 0)
                .putString(KEY_SNAPSHOT_FIRST_TOKEN, snapshot?.nodes?.firstOrNull()?.token)
                .putString(KEY_ACTION_TOKEN, actionToken)
                .putInt(KEY_ACTIONS_ATTEMPTED, active?.attemptedActions ?: -1)
                .putInt(KEY_MAX_ACTIONS, active?.scope?.maxActions ?: -1)
                .putString(KEY_LAST_STOP_REASON, center.lastStopReason()?.name)
                .putBoolean(KEY_SNAPSHOT_TRUNCATED, snapshot?.truncated ?: false)
                .putString(
                    KEY_SNAPSHOT_SUMMARY,
                    snapshot
                        ?.nodes
                        ?.joinToString(" | ") { node ->
                            listOf(
                                node.className,
                                node.text,
                                node.contentDescription,
                                node.viewId,
                                node.clickable,
                                node.editable,
                                node.scrollable,
                            ).joinToString(";")
                        },
                ).commit(),
        ) {
            "failed to persist the automation device-test bridge result"
        }
    }

    companion object {
        const val ACTION_PROBE = "com.helix.tools.automation.test.PROBE"
        const val ACTION_REPLACE_ALLOWLIST = "com.helix.tools.automation.test.REPLACE_ALLOWLIST"
        const val ACTION_START = "com.helix.tools.automation.test.START"
        const val ACTION_STOP = "com.helix.tools.automation.test.STOP"
        const val ACTION_SNAPSHOT = "com.helix.tools.automation.test.SNAPSHOT"
        const val ACTION_FIND = "com.helix.tools.automation.test.FIND"
        const val ACTION_NODE = "com.helix.tools.automation.test.NODE_ACTION"
        const val ACTION_FIND_AND_NODE = "com.helix.tools.automation.test.FIND_AND_NODE_ACTION"
        const val ACTION_GLOBAL = "com.helix.tools.automation.test.GLOBAL_ACTION"
        const val ACTION_PAUSE_PROBE = "com.helix.tools.automation.test.PAUSE_PROBE"
        const val ACTION_GENERATION_PROBE = "com.helix.tools.automation.test.GENERATION_PROBE"
        const val ACTION_RESUME = "com.helix.tools.automation.test.RESUME"
        const val ACTION_DISABLE_SERVICE = "com.helix.tools.automation.test.DISABLE_SERVICE"
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_QUERY_TEXT = "query_text"
        const val EXTRA_QUERY_DESCRIPTION = "query_description"
        const val EXTRA_QUERY_CLASS = "query_class"
        const val EXTRA_QUERY_CLICKABLE = "query_clickable"
        const val EXTRA_NODE_ACTION = "node_action"
        const val EXTRA_GLOBAL_ACTION = "global_action"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_TEXT = "text"
        const val EXTRA_EXPECTED_PACKAGE = "expected_package"
        const val EXTRA_TTL_MILLIS = "ttl_millis"
        const val EXTRA_MAX_ACTIONS = "max_actions"
        const val PREFERENCES_NAME = "automation_test_control"
        const val KEY_NONCE = "nonce"
        const val KEY_RESULT = "result"
        const val KEY_ACTIVE = "active"
        const val KEY_SNAPSHOT_PACKAGE = "snapshot_package"
        const val KEY_SNAPSHOT_WINDOW = "snapshot_window"
        const val KEY_SNAPSHOT_GENERATION = "snapshot_generation"
        const val KEY_SNAPSHOT_NODE_COUNT = "snapshot_node_count"
        const val KEY_SNAPSHOT_FIRST_TOKEN = "snapshot_first_token"
        const val KEY_SNAPSHOT_TRUNCATED = "snapshot_truncated"
        const val KEY_SNAPSHOT_SUMMARY = "snapshot_summary"
        const val KEY_ACTION_TOKEN = "action_token"
        const val KEY_ACTIONS_ATTEMPTED = "actions_attempted"
        const val KEY_MAX_ACTIONS = "max_actions"
        const val KEY_LAST_STOP_REASON = "last_stop_reason"
        private const val RESULT_OK = "OK"
        private const val RESULT_NONE = "NONE"
        private const val RESULT_UNKNOWN_ACTION = "UNKNOWN_ACTION"
    }
}
