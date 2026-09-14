"""Read only the explicitly selected host-test result directories."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
total = [0, 0, 0, 0]
for module, task in [
    ("app", "testDeveloperDebugUnitTest"),
    ("core/storage", "testDebugUnitTest"),
    ("provider/api", "test"),
    ("tools/browser", "testDebugUnitTest"),
    ("runtime/proot-app", "testDebugUnitTest"),
    ("feature/browser", "testDebugUnitTest"),
]:
    counts = [0, 0, 0, 0]
    files = list((root / module / "build/test-results" / task).glob("TEST-*.xml"))
    assert files, module
    for path in files:
        result = ET.parse(path).getroot()
        for i, key in enumerate(["tests", "failures", "errors", "skipped"]):
            counts[i] += int(result.attrib.get(key, 0))
    total = [a + b for a, b in zip(total, counts)]
    print(module, counts)
print("TOTAL tests/failures/errors/skipped", total)
