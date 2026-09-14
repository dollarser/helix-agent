"""Scoped formatting for HXA-190; never format the concurrent browser/device work."""
from pathlib import Path
import os
import re
import subprocess

paths = '''app/src/developer/kotlin/com/helix/app/provider/CodexCapabilityProbe.kt
app/src/testDeveloper/kotlin/com/helix/app/provider/CodexCapabilityProbeTest.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexJobProgress.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionTransportFailure.kt
runtime/cli-client/src/test/kotlin/com/helix/runtime/cli/client/CliModelProgressCodecTest.kt
app/src/main/kotlin/com/helix/app/provider/VisionImageSource.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelProgressCodec.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelProgressClient.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelJobClient.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelJobAwaiter.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliRuntimeProtocol.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CliRuntimeService.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexPayloadJob.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionModelStream.kt
runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexPayloadJobTest.kt
runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/SubscriptionModelStreamTest.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelCatalogClient.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CliRuntimeServiceBinder.kt
core/model/src/main/kotlin/com/helix/core/model/ModelRequest.kt
provider/api/src/main/kotlin/com/helix/provider/api/ModelMetadata.kt
provider/api/src/main/kotlin/com/helix/provider/api/ModelProvider.kt
provider/api/src/main/kotlin/com/helix/provider/api/ProviderCapabilities.kt
provider/anthropic/src/main/kotlin/com/helix/provider/anthropic/AnthropicRequestEncoder.kt
app/src/developer/kotlin/com/helix/app/provider/CodexSubscriptionProvider.kt
app/src/developer/kotlin/com/helix/app/provider/SubscriptionProviderModule.kt
app/src/main/kotlin/com/helix/app/provider/ProviderConnectionProbe.kt
app/src/main/kotlin/com/helix/app/provider/ProviderModelMetadataStore.kt
app/src/main/kotlin/com/helix/app/provider/ProviderService.kt
app/src/main/kotlin/com/helix/app/provider/ProviderTestStatusStore.kt
app/src/main/kotlin/com/helix/app/provider/ProviderUiModels.kt
app/src/main/kotlin/com/helix/app/chat/ChatService.kt
app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt
app/src/main/kotlin/com/helix/app/chat/ChatScreenProjection.kt
app/src/main/kotlin/com/helix/app/ui/ComposerMenus.kt
app/src/main/kotlin/com/helix/app/ui/ComposerToolbar.kt
app/src/main/kotlin/com/helix/app/ui/ConversationComposer.kt
app/src/main/kotlin/com/helix/app/ui/ConversationSection.kt
app/src/androidTest/kotlin/com/helix/app/ui/ConversationComposerDeviceTest.kt
app/src/androidTestDeveloper/kotlin/com/helix/app/provider/CodexSubscriptionProviderRealAccountDeviceTest.kt
app/src/test/kotlin/com/helix/app/provider/ProviderModelMetadataStoreTest.kt
app/src/test/kotlin/com/helix/app/provider/ProviderRowUiModelDiscoveryTest.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelCatalog.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelPayloadCodec.kt
runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliImageSnapshot.kt
runtime/cli-client/src/test/kotlin/com/helix/runtime/cli/client/CliModelCatalogCodecTest.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexModelCatalog.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionModel.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SubscriptionNetworkForeground.kt
runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexToolNames.kt
runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexModelCatalogTest.kt
runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexToolNamesTest.kt
runtime/cli-app/src/androidTest/kotlin/com/helix/runtime/cli/app/CodexRealAccountDiagnosticTest.kt'''.splitlines()
with Path('build/debug/2026-09-10/dynamic-subscription-format.log').open('w') as output:
    result = subprocess.run(['./gradlew', 'spotlessApply', '-PspotlessIdeHook=' + ','.join(str(Path(p).resolve()) for p in paths)], stdout=output, stderr=subprocess.STDOUT)
raise SystemExit(result.returncode)
