#!/usr/bin/env python3
"""Small Java probe only, no Gradle/device task; exact locally locked JGit jar."""
from pathlib import Path
import os
import subprocess

root = Path(__file__).resolve().parents[3]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
coordinates = [("org.eclipse.jgit/org.eclipse.jgit", "7.8.0.202609011348-r"),
               ("org.slf4j/slf4j-api", "2.0.18"), ("com.googlecode.javaewah/JavaEWAH", "1.2.3"),
               ("commons-codec/commons-codec", "1.22.1")]
jars = []
for module, version in coordinates:
    found = list((cache / module / version).glob("*/*.jar"))
    if len(found) != 1:
        raise RuntimeError(f"Expected one locked jar for {module}:{version}")
    jars.append(str(found[0]))
output = root / "build/git-filter-probe"
output.mkdir(exist_ok=False)
java = Path(os.environ["JAVA_HOME"]) / "bin/java"
subprocess.run([str(java), "--class-path", os.pathsep.join(jars),
                str(Path(__file__).with_name("GitFilterProbe.java")), str(output / "repo")], check=True)
