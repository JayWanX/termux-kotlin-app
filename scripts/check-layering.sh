#!/usr/bin/env bash
#
# Verifies the layering rules that keep the optional assistant from creeping into the terminal.
#
# The assistant is an aid, not a feature of the terminal. The rule is that deleting
# app/src/main/kotlin/com/termux/app/ai together with its single mount point in TermuxActivity must
# leave a fully working terminal. These checks make that a fact rather than an intention.
#
# Rules enforced:
#   1. Only TermuxActivity may import the ai package from outside it.
#   2. The shared/emulator/view modules must not import com.termux.app at all.
#   3. The core terminal state types must not reference any assistant/LLM type.
#
# Only real `import` statements are considered, so prose in comments cannot trip the checks.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

fail=0

note() { printf '%s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; fail=1; }

# ---------------------------------------------------------------------------
# Rule 1: outside the ai package, only TermuxActivity may import it.
# ---------------------------------------------------------------------------
ai_leaks=$(
    grep -rln '^import com\.termux\.app\.ai\.' app/src/main/kotlin --include='*.kt' \
        | grep -v '/app/ai/' \
        | grep -v 'TermuxActivity\.kt' \
        || true
)

if [ -n "$ai_leaks" ]; then
    err "the assistant package is imported from files other than TermuxActivity:"
    printf '  %s\n' $ai_leaks >&2
else
    note "ok: only TermuxActivity imports the assistant package"
fi

# ---------------------------------------------------------------------------
# Rule 2: core modules must stay independent of the app module.
# ---------------------------------------------------------------------------
core_leaks=$(
    grep -rln '^import com\.termux\.app\.' \
        termux-shared/src terminal-emulator/src terminal-view/src \
        --include='*.kt' --include='*.java' 2>/dev/null \
        || true
)

if [ -n "$core_leaks" ]; then
    err "core modules must not import com.termux.app:"
    printf '  %s\n' $core_leaks >&2
else
    note "ok: core modules do not import the app module"
fi

# ---------------------------------------------------------------------------
# Rule 3: the terminal state types must not reference assistant types.
# ---------------------------------------------------------------------------
banned='^import .*(assistant|AgentLoop|ToolCall|OpenAi|LlmProvider)'
state_leaks=$(
    grep -rniE "$banned" termux-shared/src/main/kotlin/com/termux/shared/terminal \
        --include='*.kt' 2>/dev/null \
        || true
)

if [ -n "$state_leaks" ]; then
    err "terminal state types must not import assistant types:"
    printf '  %s\n' "$state_leaks" >&2
else
    note "ok: terminal state types are free of assistant dependencies"
fi

if [ "$fail" -ne 0 ]; then
    note ""
    note "Layering check failed. See docs/AI_AGENT_COMPARISON_AND_PLAN.md for the rules."
    exit 1
fi

note "layering checks passed"
