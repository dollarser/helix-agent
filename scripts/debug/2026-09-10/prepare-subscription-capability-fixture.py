"""Create the deterministic synthetic red PNG and wire the explicit Codex capability probe."""
import base64
from pathlib import Path
import struct
import zlib

def chunk(kind, body):
    return struct.pack('!I', len(body)) + kind + body + struct.pack('!I', zlib.crc32(kind + body))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!IIBBBBB', 64, 64, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress((b'\0' + bytes([255, 0, 0]) * 64) * 64)) + chunk(b'IEND', b'')
p = Path('app/src/main/kotlin/com/helix/app/provider/VisionImageSource.kt')
s = p.read_text()
s = s.replace('if (ref == PROBE_IMAGE_REF)', 'if (ref == COLOR_PROBE_REF) return COLOR_PROBE_IMAGE\n        if (ref == PROBE_IMAGE_REF)')
s = s.replace('val PROBE_IMAGE_REF: ArtifactRef = CapabilityProbe.VISION_PROBE_REF',
    'val PROBE_IMAGE_REF: ArtifactRef = CapabilityProbe.VISION_PROBE_REF\n' +
    '        val COLOR_PROBE_REF = ArtifactRef("helix:vision-color-probe")\n' +
    '        private val COLOR_PROBE_IMAGE = LoadedImage("image/png", "' + base64.b64encode(png).decode() + '")')
p.write_text(s)
p = Path('app/src/developer/kotlin/com/helix/app/provider/SubscriptionProviderModule.kt')
s = p.read_text()
start = s.index('    private suspend fun probeCodex(')
end = s.index('    private suspend fun probeText(', start)
s = s[:start] + '    private suspend fun probeCodex(provider: CodexSubscriptionProvider): ProbeOutcome =\n        CodexCapabilityProbe(provider).run()\n\n' + s[end:]
p.write_text(s)
p = Path('app/src/androidTestDeveloper/kotlin/com/helix/app/provider/CodexSubscriptionProviderRealAccountDeviceTest.kt')
s = p.read_text()
start = s.index('            val directBinding =')
end = s.index('            val providerId =', start)
s = s[:start] + s[end:]
p.write_text(s)
