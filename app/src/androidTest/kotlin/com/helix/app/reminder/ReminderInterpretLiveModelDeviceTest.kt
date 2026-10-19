package com.helix.app.reminder

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.ConfirmedTextAnalysis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Explicit opt-in, synthetic requests only; no persistence or platform delivery service is used. */
class ReminderInterpretLiveModelDeviceTest {
    @Test fun realModelUsesReferenceTimeAndClarifiesAmbiguity(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val optIn = InstrumentationRegistry.getArguments().getString("helix.reminderInterpretLive")
            org.junit.Assume.assumeTrue("Explicit live-model opt-in is absent", optIn != null)
            require(optIn == "true")
            val providers =
                (instrumentation.targetContext.applicationContext as HelixApplication)
                    .awaitAppContainer()
                    .providerService
            providers.refreshModelCatalog(BuiltInModel.PROVIDER_ID)
            withTimeout(30_000) {
                providers.rows.first { rows ->
                    rows.any {
                        it.id == BuiltInModel.PROVIDER_ID &&
                            it.modelSelectable(BuiltInModel.MODEL_ID)
                    }
                }
            }
            val interpreter = ReminderInterpreter(ConfirmedTextAnalysis(providers))
            val reference = Instant.parse("2030-01-01T08:00:00Z")
            val zone = ZoneId.of("Asia/Shanghai")
            val prepared =
                interpreter.prepare(
                    "明天下午三点提醒我完成合成测试任务",
                    reference,
                    zone,
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            val result = prepared.interpret() as ReminderInterpretation.Proposal
            assertEquals(
                "2030-01-02T15:00",
                java.time.LocalDateTime
                    .parse(result.reminder.localTime)
                    .toString(),
            )
            assertEquals("Asia/Shanghai", result.reminder.zone)
            assertEquals("ONCE", result.reminder.repeat)
            assertEquals("", result.reminder.id)
            val unclear =
                interpreter.prepare(
                    "明天下午提醒我完成合成测试任务",
                    reference,
                    zone,
                    BuiltInModel.PROVIDER_ID,
                    BuiltInModel.MODEL_ID,
                )
            assertTrue(unclear.interpret() is ReminderInterpretation.Clarification)
            android.util.Log.i(
                "HelixReminderEvidence",
                "interpret-live=${BuiltInModel.MODEL_ID} exactTime=true clarification=true persisted=false " +
                    "inputHash=${prepared.request.inputHash}",
            )
        }
}
