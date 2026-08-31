package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The first-launch privacy notice (HXA-028 “首次启动隐私说明”; ADR-0006: fresh
 * install / data reset → STANDARD + this flow). It blocks the main UI until the
 * user explicitly acknowledges; the caller persists the acknowledgement via
 * [com.helix.app.FirstLaunchStore]. The text states only what M2 actually does —
 * no capability is advertised before its milestone.
 */
@Composable
@Suppress("FunctionName")
fun FirstLaunchNoticeScreen(onContinue: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp)
                .testTag("first-launch-notice"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("首次使用须知", style = MaterialTheme.typography.headlineSmall)
        NOTICE_SECTIONS.forEach { section ->
            Text(
                text = section,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Justify,
            )
        }
        Button(
            onClick = onContinue,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag("first-launch-continue"),
        ) {
            Text("我已知晓，开始使用")
        }
    }
}

private val NOTICE_SECTIONS: List<String> =
    listOf(
        "Helix 在你这台设备上本地运行：会话、Provider 配置、密钥与记录都只保存在本机，没有云同步。",
        "模型请求只会发往你显式配置的 Provider 端点。界面始终显示该请求的目的地（origin）与数据驻留" +
            "（本机 / 局域网 / 公共云）（FR-LLM-009）。",
        "凭据形态内容（API key、OAuth token、Cookie、密码、私钥等）在发送前被拒绝；" +
            "两个安全配置下都拒绝发送（ADR-0005）。",
        "高敏感数据逐次确认；Standard 配置不提供永久允许。首次安装与数据重置后的默认安全配置均为 Standard。",
    )
