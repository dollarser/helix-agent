"""One-time HXA-190 image-envelope implementation edit; do not replay on final source."""
from pathlib import Path
p=Path('runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelPayloadCodec.kt');s=p.read_text()
s=s.replace('val request: ModelRequest,\n)', 'val request: ModelRequest,\n    val images: List<CliImageSnapshot> = emptyList(),\n)',1)
s=s.replace('const val MAX_BYTES = 512 * 1024','const val MAX_BYTES = 16 * 1024 * 1024\n    const val MAX_TEXT_BYTES = 512 * 1024',1)
s=s.replace('provider: CliModelProvider = CliModelProvider.CODEX,\n    ): ByteArray', 'provider: CliModelProvider = CliModelProvider.CODEX,\n        images: List<CliImageSnapshot> = emptyList(),\n    ): ByteArray',1)
a=s.index('        require(\n            request.messages.none');b=s.index('        val bytes =',a)
s=s[:a]+'''        val references = request.messages.flatMap { it.images }.toSet()
        require(images.map { it.reference }.toSet() == references && images.size == references.size) {
            "image snapshots must exactly match message references"
        }
        require(images.isEmpty() || provider == CliModelProvider.CODEX)
        require(images.sumOf { it.base64.length.toLong() } <= com.helix.core.model.VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES)
        val version = if (images.isNotEmpty()) 3 else if (provider == CliModelProvider.CODEX) 1 else 2
'''+s[b:]
s=s.replace('put("version", if (provider == CliModelProvider.CODEX) 1 else 2)','put("version", version)\n                if (version == 3) put("images", encodeImages(images))',1)
s=s.replace('add(encodeMessage(it))','add(encodeMessage(it, version == 3))',1)
s=s.replace('require(bytes.size <= MAX_BYTES) { "model request exceeds IPC limit" }','require(bytes.size <= if (version == 3) MAX_BYTES else MAX_TEXT_BYTES) { "model request exceeds IPC limit" }',1)
s=s.replace('require(version == 1L || version == 2L)','require(version in 1L..3L)\n        require(version == 3L || bytes.size <= MAX_TEXT_BYTES)',1)
s=s.replace('root.strictObject(if (version == 1L) REQUEST_KEYS else REQUEST_KEYS + "providerId")','root.strictObject(when (version) { 1L -> REQUEST_KEYS; 2L -> REQUEST_KEYS + "providerId"; else -> REQUEST_KEYS + "images" })',1)
s=s.replace('if (version == 1L) {','if (version != 2L) {',1)
s=s.replace('.jsonArray.map(::decodeMessage)', '.jsonArray.map { decodeMessage(it, version == 3L) }',1)
s=s.replace('return CliModelEnvelope(provider, request)','''val images = if (version == 3L) decodeImages(root.getValue("images").jsonArray) else emptyList()
        val references = request.messages.flatMap { it.images }.toSet()
        require(images.map { it.reference }.toSet() == references && images.size == references.size)
        require(images.sumOf { it.base64.length.toLong() } <= com.helix.core.model.VisionLimits.MAX_TOTAL_BASE64_PER_REQUEST_BYTES)
        return CliModelEnvelope(provider, request, images)''',1)
s=s.replace('private fun encodeMessage(message: ModelMessage): JsonObject =','private fun encodeMessage(message: ModelMessage, withImages: Boolean): JsonObject =',1)
s=s.replace('put("role", message.role.name)','''put("role", message.role.name)
            if (withImages) put("images", buildJsonArray { message.images.forEach { image ->
                add(buildJsonObject { put("ref", image.ref.value); put("mediaType", image.mediaType) })
            } })''',1)
s=s.replace('private fun decodeMessage(element: kotlinx.serialization.json.JsonElement): ModelMessage {\n        val obj = element.strictObject(MESSAGE_KEYS)','private fun decodeMessage(element: kotlinx.serialization.json.JsonElement, withImages: Boolean): ModelMessage {\n        val obj = element.strictObject(if (withImages) MESSAGE_KEYS + "images" else MESSAGE_KEYS)',1)
s=s.replace('role = ModelRole.valueOf(obj.getValue("role").jsonPrimitive.content),','''role = ModelRole.valueOf(obj.getValue("role").jsonPrimitive.content),
            images = if (withImages) obj.getValue("images").jsonArray.map { value ->
                val image = value.strictObject(setOf("ref", "mediaType"))
                com.helix.core.model.ImageReference(com.helix.core.model.ArtifactRef(image.getValue("ref").jsonPrimitive.content), image.getValue("mediaType").jsonPrimitive.content)
            } else emptyList(),''',1)
a=s.index('    private val REQUEST_KEYS =')
s=s[:a]+'''    private fun encodeImages(images: List<CliImageSnapshot>) = buildJsonArray {
        images.forEach { image -> add(buildJsonObject {
            put("ref", image.reference.ref.value)
            put("mediaType", image.reference.mediaType)
            put("base64", image.base64)
        }) }
    }

    private fun decodeImages(images: JsonArray): List<CliImageSnapshot> {
        require(images.size <= 4)
        return images.map { value ->
            val image = value.strictObject(setOf("ref", "mediaType", "base64"))
            CliImageSnapshot(com.helix.core.model.ImageReference(com.helix.core.model.ArtifactRef(image.getValue("ref").jsonPrimitive.content),
                image.getValue("mediaType").jsonPrimitive.content), image.getValue("base64").jsonPrimitive.content)
        }
    }

'''+s[a:];p.write_text(s)
p=Path('runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliModelJobClient.kt');s=p.read_text().replace('provider: CliModelProvider = CliModelProvider.CODEX,\n    ): AwaitOutcome', 'provider: CliModelProvider = CliModelProvider.CODEX,\n        images: List<CliImageSnapshot> = emptyList(),\n    ): AwaitOutcome',1).replace('CliModelRequestCodec.encode(request, provider)','CliModelRequestCodec.encode(request, provider, images)',1);p.write_text(s)
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexPayloadJob.kt');s=p.read_text().replace('MAX_PAYLOAD_BYTES = 8L * 1024L * 1024L','MAX_PAYLOAD_BYTES = 64L * 1024L * 1024L');p.write_text(s)
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CodexSubscriptionModel.kt');s=p.read_text().replace('client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),','client: OkHttpClient = OkHttpClient.Builder().dns(BoundedDnsCache()).build(),\n    images: List<com.helix.runtime.cli.client.CliImageSnapshot> = emptyList(),',1).replace('private val encoder = ResponsesRequestEncoder { error("image references are rejected by the IPC codec") }','''private val imageSnapshots = images.associate { it.reference to it.base64 }
    private val encoder = ResponsesRequestEncoder { reference ->
        com.helix.provider.openai.responses.ImagePayload.Base64(requireNotNull(imageSnapshots[reference]) { "image snapshot missing" })
    }''',1);p.write_text(s)
p=Path('runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/CliRuntimeService.kt');s=p.read_text().replace('CodexSubscriptionModel(vault, oauth).also(activeModel::set)','CodexSubscriptionModel(vault, oauth, images = envelope.images).also(activeModel::set)');p.write_text(s)
p=Path('scripts/check-cli-runtime-boundary.sh');s=p.read_text().replace("rg -F 'const val MAX_BYTES = 512 * 1024'", "rg -F 'const val MAX_TEXT_BYTES = 512 * 1024'");p.write_text(s)
