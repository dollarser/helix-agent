#!/usr/bin/env python3
"""Select CI scope conservatively; a cancelled code push must not hide behind docs."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from urllib.error import URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[2]


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def is_ancestor(sha):
    return bool(re.fullmatch(r'[0-9a-f]{40}', sha)) and subprocess.run(
        ['git', 'merge-base', '--is-ancestor', sha, 'HEAD'], cwd=ROOT,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


def classify(paths):
    if not paths:
        return 'full'
    scope = 'source'
    for name in paths:
        path = Path(name)
        if name.startswith(('docs/', 'scripts/debug/')) or name in {'README.md', 'AGENTS.md', 'LICENSE'}:
            continue
        if (name.startswith(('.github/', 'gradle/')) or path.name == 'AndroidManifest.xml'
                or path.suffix in {'.gradle', '.kts', '.pro', '.c', '.cpp', '.h', '.so', '.aar', '.jar'}
                or re.search(r'/src/[^/]*release[^/]*/', name, re.IGNORECASE) or 'runtime-lock' in name or path.name == 'gradle.lockfile'):
            return 'full'
        if '/src/' in name and path.suffix in {'.kt', '.java', '.xml', '.json', '.txt', '.png', '.webp', '.svg'}:
            scope = 'debug'
        else:
            # CI scripts, unknown extensions and new infrastructure default to full.
            return 'full'
    return scope


def successful_android_jobs(jobs):
    required = {'source', 'runtime-assets', 'android (analysis)', 'android (tests-build)', 'verify'}
    outcomes = {job['name']: job.get('conclusion') for job in jobs}
    return all(outcomes.get(name) == 'success' for name in required)


def proven_base(get, ancestor):
    # Bounded discovery. No prior qualifying run or unavailable API means full CI.
    runs = get('actions/workflows/ci.yml/runs?branch=main&status=success&per_page=30')['workflow_runs']
    for run in runs:
        if run.get('event') != 'push' or not ancestor(run['head_sha']):
            continue
        jobs = get(f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100")
        if jobs.get('total_count', 0) > 100:
            continue
        if successful_android_jobs(jobs['jobs']):
            return run['head_sha']
    return None


def api_get(path):
    repo = os.environ['GITHUB_REPOSITORY']
    request = Request(
        f'https://api.github.com/repos/{repo}/{path}',
        headers={'Accept': 'application/vnd.github+json',
                 'Authorization': 'Bearer ' + os.environ['GH_TOKEN'],
                 'X-GitHub-Api-Version': '2022-11-28'},
    )
    with urlopen(request, timeout=15) as response:
        return json.load(response)


def plan(event_name, event, get=api_get):
    base = None
    if event_name == 'workflow_dispatch':
        requested = event.get('inputs', {}).get('scope', 'full')
        if requested not in {'debug', 'full'}:
            raise ValueError('Manual scope must be debug or full')
        return {'scope': requested, 'reason': 'explicit manual verification', 'base': None, 'paths': []}
    try:
        if event_name == 'pull_request':
            sha = event['pull_request']['base']['sha']
            if not re.fullmatch(r'[0-9a-f]{40}', sha):
                raise ValueError('Invalid PR base SHA')
            base = git('merge-base', sha, 'HEAD')
        elif event_name == 'push':
            base = proven_base(get, is_ancestor)
        if base:
            paths = git('diff', '--name-only', '--no-renames', base, 'HEAD').splitlines()
            return {'scope': classify(paths), 'reason': 'changes since verified base' if event_name == 'push'
                    else 'complete pull request diff', 'base': base, 'paths': paths}
    except (URLError, OSError, KeyError, ValueError, subprocess.CalledProcessError):
        # Do not log token-bearing requests or quietly skip when discovery fails.
        pass
    return {'scope': 'full', 'reason': 'no verifiable baseline; conservative full gate', 'base': None, 'paths': []}


def verify(scope, source, assets, android):
    if source != 'success' or scope not in {'source', 'debug', 'full'}:
        return False
    expected = 'skipped' if scope == 'source' else 'success'
    return assets == expected and android == expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if args.verify:
        valid = verify(*(os.environ.get(name, '') for name in
                         ('CI_SCOPE', 'SOURCE_RESULT', 'ASSETS_RESULT', 'ANDROID_RESULT')))
        if not valid:
            raise SystemExit('Required CI gates failed, were cancelled, or were unexpectedly skipped')
        print('Required CI gates passed for the selected scope')
        return
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    result = plan(os.environ['GITHUB_EVENT_NAME'], event)
    Path('build/ci').mkdir(parents=True, exist_ok=True)
    Path('build/ci/plan.json').write_text(json.dumps(result, indent=2) + '\n')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('scope=' + result['scope'] + '\n')
    print(json.dumps({key: value for key, value in result.items() if key != 'paths'}))


if __name__ == '__main__':
    main()
