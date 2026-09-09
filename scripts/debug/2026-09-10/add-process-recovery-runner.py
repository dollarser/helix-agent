from pathlib import Path
p=Path('scripts/debug/2026-09-09/run-owned-emulator.py');s=p.read_text();s=s.replace('            extras = []','''            if args.recovery_setup_class:
                setup = device("shell", "am", "instrument", "-w", "-e", "class", args.recovery_setup_class,
                               "-e", "recoveryPhase", "setup", args.runner, timeout=args.timeout)
                (output / "recovery-setup.txt").write_text(setup)
                if not passed(setup):
                    raise RuntimeError("Recovery setup did not pass")
                app_package = args.runner.split("/", 1)[0].removesuffix(".test")
                old_pid = device("shell", "pidof", app_package).strip()
                if not old_pid:
                    raise RuntimeError("No setup process to stop")
                device("shell", "am", "force-stop", app_package)
                (output / "process-stop.txt").write_text("force-stop owned test package; previous pid=" + old_pid)
            extras = []''');s=s.replace('    parser.add_argument("--classes", required=True)','    parser.add_argument("--classes", required=True)\n    parser.add_argument("--recovery-setup-class")');p.write_text(s)
