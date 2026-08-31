package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTestMappingTest {
    @Test
    fun everyErrorCodeMapsToAUserVisibleLabel() {
        for (code in ModelErrorCode.entries) {
            val label = ConnectionTestMapping.codeLabel(code)
            require(label.isNotBlank()) { "no label for $code" }
        }
        assertEquals("网络/TLS 连接失败", ConnectionTestMapping.codeLabel(ModelErrorCode.TRANSPORT))
        assertEquals("认证失败（key 缺失或无效）", ConnectionTestMapping.codeLabel(ModelErrorCode.AUTH))
        assertEquals("服务限流（稍后重试）", ConnectionTestMapping.codeLabel(ModelErrorCode.RATE_LIMITED))
    }

    @Test
    fun theFourProbePhasesHaveStableLabels() {
        assertEquals("网络与认证", ConnectionTestMapping.phaseLabel(1))
        assertEquals("模型列表", ConnectionTestMapping.phaseLabel(2))
        assertEquals("最小文本流", ConnectionTestMapping.phaseLabel(3))
        assertEquals("最小工具调用", ConnectionTestMapping.phaseLabel(4))
    }

    @Test
    fun chipTextDistinguishesTheThreeStates() {
        assertEquals("未测试", ConnectionTestStatus.Untested.chipText())
        val passed =
            ConnectionTestStatus.Passed(
                atMillis = 1L,
                capabilities =
                    ProviderCapabilities(
                        streaming = true,
                        toolCalls = false,
                        parallelToolCalls = false,
                        vision = false,
                        reasoning = false,
                        jsonSchemaOutput = false,
                        maxContextTokens = null,
                        source = CapabilitySource.PROBED,
                    ),
            )
        assertEquals("已通过 · 能力已探测", passed.chipText())
        val failed = ConnectionTestStatus.Failed(1L, phase = 3, codeLabel = "网络/TLS 连接失败", retryable = true)
        assertEquals("测试未通过（最小文本流）", failed.chipText())
    }
}
