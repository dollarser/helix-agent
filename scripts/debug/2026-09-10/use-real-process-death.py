from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/FileTransferRecoveryDeviceTest.kt');s=p.read_text().replace('                    if (destination == "work/recovery-device/target") throw SimulatedInterruption()', '''                    if (destination == "work/recovery-device/target") {
                        if (phase == "setup") {
                            marker.writeText(Process.myPid().toString())
                            Process.killProcess(Process.myPid())
                        }
                        throw SimulatedInterruption()
                    }''');p.write_text(s)
p=Path('scripts/debug/2026-09-09/run-owned-emulator.py');s=p.read_text();a=s.index('                if not passed(setup):');b=s.index('            extras = []',a);s=s[:a]+'''                if "process crashed" not in setup.lower():
                    raise RuntimeError("Recovery setup did not reach the expected process death")
                app_package = args.runner.split("/", 1)[0].removesuffix(".test")
                old_pid = device("shell", "run-as", app_package, "cat", "no_backup/recovery-device-pid").strip()
                if not old_pid.isdigit():
                    raise RuntimeError("Missing durable setup process identity")
                (output / "process-stop.txt").write_text("Process.killProcess at publication; previous pid=" + old_pid)
'''+s[b:];p.write_text(s)
