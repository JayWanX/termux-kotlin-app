"""Validate the GitHub workflow files.

Two classes of mistake are checked, both of which have actually happened in this repository:

1. A step that hides failures (`continue-on-error`). `coverage.yml` ran the tests that way, so a red
   suite reported a green job for as long as the workflow existed.
2. A step that runs a Gradle task which does not exist. The same workflow asked for
   `jacocoTestReport` at the root, where no such task is registered, so the step failed on its first
   line while claiming to publish coverage that was never generated.

The task check is a lexical comparison against the task names declared in the build scripts, which is
approximate: it cannot see tasks contributed by plugins or generated at configuration time. It is
deliberately biased towards false negatives — a name it cannot find is reported as a warning, not a
failure — because a guard that blocks merges over a plugin-provided task would be worse than no guard.

It cannot catch a task that exists in one module but not at the root, which is the shape of the bug
that made `jacocoTestReport` fail here: the task simply did not exist anywhere at the time. Gradle
resolves an unqualified task name across all projects, so `jacocoTestReport` works from the root as
soon as any module declares it — the qualification in coverage.yml is precision, not a requirement.
"""

import re
import sys
from pathlib import Path

import yaml

WORKFLOW_FILES = [
    ".github/workflows/coverage.yml",
    ".github/workflows/run_tests.yml",
]

# Tasks that Gradle or the Android plugin always provide, so the lexical scan will not see them.
BUILTIN_TASKS = {
    "test",
    "build",
    "clean",
    "assemble",
    "assembleDebug",
    "assembleRelease",
    "check",
    "lint",
    "connectedAndroidTest",
    "testDebugUnitTest",
    "testReleaseUnitTest",
    "installDebug",
    "preBuild",
}

failures = []
warnings = []


def declared_tasks(root: Path) -> set:
    """Task names mentioned in any build script, by declaration or by type registration."""
    names = set()
    patterns = [
        re.compile(r"tasks\.register\(\s*['\"]([A-Za-z0-9_.]+)['\"]"),
        re.compile(r"tasks\.named\(\s*['\"]([A-Za-z0-9_.]+)['\"]"),
        re.compile(r"task\s+([A-Za-z0-9_]+)\s*\("),
        re.compile(r"task\s+([A-Za-z0-9_]+)\s*\{"),
    ]
    for script in root.rglob("*.gradle"):
        # `.gradle` is also the name of a build-state directory, which rglob matches as a path.
        if not script.is_file():
            continue
        try:
            text = script.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for pattern in patterns:
            names.update(pattern.findall(text))
    return names | BUILTIN_TASKS


def gradle_invocations(command: str):
    """Yield the task tokens from a shell line that runs Gradle."""
    for line in command.splitlines():
        line = line.strip()
        if not (line.startswith("./gradlew") or line.startswith("gradle ")):
            continue
        tokens = line.split()
        skip_next = False
        for token in tokens:
            if skip_next:
                skip_next = False
                continue
            if token.startswith("-"):
                # Skip a flag's value, e.g. `-Pfoo bar` or `--project-dir dir`.
                if token in {"-p", "--project-dir", "-P", "--project-prop"}:
                    skip_next = True
                continue
            # The launcher itself, in either spelling, is never a task.
            if token in {"gradlew", "gradle", "./gradlew"}:
                continue
            yield token


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    known = declared_tasks(root)

    for path in WORKFLOW_FILES:
        full = root / path
        if not full.exists():
            failures.append(f"{path}: file is missing")
            continue

        doc = yaml.safe_load(full.read_text(encoding="utf-8"))
        jobs = doc.get("jobs") or {}
        if not jobs:
            failures.append(f"{path}: no jobs found")
            continue

        for job_name, job in jobs.items():
            steps = job.get("steps") or []
            print(f"{path} :: {job_name} ({len(steps)} steps)")

            for step in steps:
                name = step.get("name", "<unnamed>")
                condition = step.get("if", "-")
                print(f"   - {name:<38} if={condition}")

                if step.get("continue-on-error"):
                    failures.append(
                        f"{path}: step '{name}' sets continue-on-error, which hides failures"
                    )

                command = step.get("run")
                if not command:
                    continue

                for task in gradle_invocations(command):
                    if ":" in task:
                        # Module-qualified: compare only the task part.
                        bare = task.rsplit(":", 1)[-1]
                    else:
                        bare = task
                    if bare in known:
                        continue
                    warnings.append(
                        f"{path}: step '{name}' runs task '{task}', which no build script declares"
                    )

    print()
    for warning in warnings:
        print(f"WARN: {warning}")
    for failure in failures:
        print(f"ERROR: {failure}")

    if failures:
        print("\nFAIL")
        return 1
    print("OK: workflows parse, hide no failures, and reference declared tasks")
    return 0


if __name__ == "__main__":
    sys.exit(main())
