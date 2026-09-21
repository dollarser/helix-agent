#!/usr/bin/env python3
"""Small isolated compiler/JUnit probe for five pure Git reader files; not the Android/Gradle gate."""
import argparse
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(module, version):
    found = list((cache / module / version).glob("*/*.jar"))
    if len(found) != 1:
        raise RuntimeError(f"Expected one jar for {module}:{version}")
    return str(found[0])


compiler = [jar("org.jetbrains.kotlin/" + name, "2.3.21") for name in
            ("kotlin-compiler-embeddable", "kotlin-stdlib", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [jar("org.jetbrains.kotlin/kotlin-reflect", "1.6.10"),
             jar("org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm", "1.8.0"), jar("org.jetbrains/annotations", "13.0")]
runtime = [jar("org.jetbrains.kotlin/kotlin-stdlib", "2.3.21"), jar("org.jetbrains/annotations", "13.0"),
           jar("org.eclipse.jgit/org.eclipse.jgit", "7.8.0.202609011348-r"),
           jar("org.slf4j/slf4j-api", "2.0.18"), jar("com.googlecode.javaewah/JavaEWAH", "1.2.3"),
           jar("commons-codec/commons-codec", "1.22.1"), jar("junit/junit", "4.13.2"),
           jar("org.hamcrest/hamcrest-core", "1.3")]
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
sources = sorted((root / "app/src/main/kotlin/com/helix/app/git").glob("*.kt"))
sources.append(root / "app/src/test/kotlin/com/helix/app/git/GitWorkspaceReaderTest.kt")
subprocess.run([java, "-Xmx256m", "-cp", os.pathsep.join(compiler), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath", os.pathsep.join(runtime),
                "-d", str(args.output), *map(str, sources)], check=True)
subprocess.run([java, "-Xmx128m", "-cp", os.pathsep.join([str(args.output), *runtime]),
                "org.junit.runner.JUnitCore", "com.helix.app.git.GitWorkspaceReaderTest"], check=True)
