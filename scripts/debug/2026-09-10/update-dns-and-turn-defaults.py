"""Update opt-in DNS form defaults and ordinary Turn defaults without changing Goal semantics."""
from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/runcontrol/RunControlStore.kt');s=p.read_text()
s=s.replace('val DEFAULT = TurnBudgets(32, 33, 128_000, 4_096, 160_000)', 'val DEFAULT = TurnBudgets(32, 48, 1_000_000, 16_384, 1_000_000)\n    internal val PREVIOUS_DEFAULT = TurnBudgets(32, 33, 128_000, 4_096, 160_000)')
s=s.replace('"budgets_v2",','"budgets_v3",')
s=s.replace('lines[4] == "budgets_v2"','lines[4] in setOf("budgets_v2", "budgets_v3")')
s=s.replace('if (lines.size < 5 && it == TurnBudgetBounds.LEGACY_DEFAULT) TurnBudgetBounds.DEFAULT else it','''when {
                        lines.size < 5 && it == TurnBudgetBounds.LEGACY_DEFAULT -> TurnBudgetBounds.DEFAULT
                        lines.getOrNull(4) != "budgets_v3" && it == TurnBudgetBounds.PREVIOUS_DEFAULT ->
                            TurnBudgetBounds.DEFAULT
                        else -> it
                    }''')
p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt');s=p.read_text().replace('minOf(DEFAULT_MAX_OUTPUT_TOKENS, control.budgets.maxOutputTokens)','control.budgets.maxOutputTokens').replace('        const val DEFAULT_MAX_OUTPUT_TOKENS = 4_096L\n','');p.write_text(s)
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionNetworkSettingsActivity.kt');s=p.read_text();s=s.replace('hint = "chatgpt.com"','setText("chatgpt.com")');s=s.replace('                setMinLines(2)','''                // Editable, opt-in preset verified 2026-09-10; never installed on app startup.
                val existing = settings.entries().firstOrNull { it.hostname == "chatgpt.com" }
                setText(existing?.addresses?.joinToString("\\n") ?: "104.18.32.47\\n172.64.155.209")
                setMinLines(2)''');p.write_text(s)
p=Path('app/src/test/kotlin/com/helix/app/runcontrol/RunControlStoreTest.kt');s=p.read_text().replace('assertEquals(33, PersistedRunControlStore(lines).current.budgets.maxModelCalls)','assertEquals(48, PersistedRunControlStore(lines).current.budgets.maxModelCalls)');p.write_text(s)
