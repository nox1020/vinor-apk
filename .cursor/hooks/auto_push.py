#!/usr/bin/env python3
"""Commit leftover work and push only the current feature branch.

Cursor runs this as a ``stop`` hook (``.cursor/hooks.json``). stdin is a JSON
object with ``status`` (``completed``, ``aborted``, or ``error``) and
``loop_count``. stdout must be one JSON object. The documented ``stop`` output
field is ``followup_message``; Cursor submits it as the next user message only
when the process exits 0 and ``status`` is ``completed``.

Exit codes (Cursor command hooks):

- 0: success; stdout JSON is applied
- 2: block, fail-closed (same idea as ``permission: "deny"``)
- anything else: fail-open; the agent is allowed to stop and JSON is ignored

A rejected or incomplete push prints the reason on stderr and exits 2. It must
not exit 0. ``main`` and ``master`` are never pushed.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

HOOKS = Path(__file__).resolve().parent
if str(HOOKS) not in sys.path:
    sys.path.insert(0, str(HOOKS))

from ci_watch import snapshot_ci  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
REFUSE_MAIN = "auto_push: refusing to push main; use a PR branch"
CI_FOLLOWUP = (
    "The current cursor/* branch was pushed to origin. Open a pull request "
    "if one is not already open. Never commit or push main or master, and "
    "never force-push. Then run `python .cursor/hooks/ci_watch.py --wait`. "
    "If it reports failure, read the FAILED_JOB log file, fix the root cause "
    "(do not weaken or skip the workflow), commit, push this same branch, and "
    "repeat until green. If CI_STATE is none, no workflow ran for this commit "
    "— stop. If blocked by missing secrets/auth, report that and stop."
)
SECRET_RE = re.compile(
    r"(?:^|[/\\])(?:"
    r"\.env(?:\..*)?"
    r"|[^/\\]+\.(?:pem|key|p12|jks|keystore|pkcs12)"
    r"|credentials\.json"
    r"|google-services\.json"
    r"|firebase-service-account\.json"
    r"|key\.properties"
    r"|\.git-token"
    r"|service-account[^/\\]*\.json"
    r")$",
    re.IGNORECASE,
)
PROTECTED = {"main", "master"}


def log(message: str) -> None:
    print(f"[auto-push] {redact(message)}", file=sys.stderr, flush=True)


def redact(text: str) -> str:
    """Strip credentials that git sometimes echoes inside remote URLs."""
    text = re.sub(r"https?://[^/\s@]+@", "https://[redacted]@", text)
    text = re.sub(
        r"\b(?:ghs_|github_pat_|ghp_|glpat-)[A-Za-z0-9_]+\b",
        "[redacted]",
        text,
    )
    return text


def emit(followup: str | None = None) -> None:
    payload: dict[str, str] = {}
    if followup:
        payload["followup_message"] = redact(followup)
    sys.stdout.write(json.dumps(payload) + "\n")
    sys.stdout.flush()


def fail(message: str, *, followup: str | None = None) -> int:
    """Surface a push failure. Exit 2 so a stop hook cannot look successful."""
    clean = redact(message).strip()
    print(clean, file=sys.stderr, flush=True)
    emit(followup or clean)
    return 2


def git(*args: str, check: bool = False) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=REPO,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=check,
    )


def git_ok(*args: str) -> str:
    result = git(*args)
    if result.returncode != 0:
        detail = redact((result.stderr or result.stdout or "").strip())
        raise RuntimeError(detail or f"git {' '.join(args)} failed")
    return result.stdout


def in_progress_git_state(git_dir: Path) -> bool:
    # Only directory/lock markers — leftover refs like REBASE_HEAD can remain
    # after a finished rebase and would otherwise block auto-push.
    return any(
        (git_dir / name).exists()
        for name in ("MERGE_HEAD", "rebase-merge", "rebase-apply", "CHERRY_PICK_HEAD")
    )


def current_branch() -> str | None:
    """Branch name, or None when HEAD is detached."""
    symbolic = git("symbolic-ref", "-q", "HEAD")
    if symbolic.returncode != 0:
        return None
    ref = symbolic.stdout.strip()
    prefix = "refs/heads/"
    if not ref.startswith(prefix):
        return None
    name = ref[len(prefix) :]
    return name or None


def unstage_secrets() -> None:
    staged = git_ok("diff", "--cached", "--name-only", "-z").split("\0")
    secrets = [path for path in staged if path and SECRET_RE.search(path)]
    if not secrets:
        return
    git("reset", "-q", "HEAD", "--", *secrets)
    log("skipped secrets: " + ", ".join(secrets))


def refuse_protected(reason: str) -> int:
    # Exact line for main, master, and detached HEAD. No fetch and no push.
    print(REFUSE_MAIN, file=sys.stderr, flush=True)
    emit(
        REFUSE_MAIN
        + f" ({reason}). Create a cursor/* branch, commit there, push only "
        "that branch, and open a pull request. Never commit or push main."
    )
    return 2


def push_feature_branch(branch: str) -> int:
    if branch in PROTECTED or branch.startswith("-"):
        return refuse_protected(f"refusing ref {branch!r}")

    fetched = git("fetch", "origin")
    if fetched.returncode != 0:
        detail = redact((fetched.stderr or fetched.stdout or "fetch failed").strip())
        return fail(
            f"auto_push: fetch failed\n{detail}",
            followup=(
                "auto_push: git fetch origin failed. Nothing was pushed. "
                f"{detail} Fix the fetch error and push this cursor/* branch again. "
                "Never push main and never force-push."
            ),
        )

    refspec = f"HEAD:refs/heads/{branch}"
    pushed = git("push", "-u", "origin", refspec)
    if pushed.returncode != 0:
        detail = redact((pushed.stderr or pushed.stdout or "push failed").strip())
        return fail(
            f"auto_push: push rejected\n{detail}",
            followup=(
                "auto_push: push rejected for "
                f"origin/{branch}. {detail} The commits are still only local. "
                "Do not force-push and do not push main. Fix the branch and run "
                f"`git push -u origin HEAD:refs/heads/{branch}` again."
            ),
        )

    ahead = git("rev-list", "--count", f"origin/{branch}..HEAD")
    if ahead.returncode != 0:
        detail = redact((ahead.stderr or ahead.stdout or "rev-list failed").strip())
        return fail(
            f"auto_push: push did not update origin/{branch}\n{detail}",
            followup=(
                f"auto_push: could not confirm origin/{branch} contains HEAD. {detail} "
                "Do not force-push and do not push main."
            ),
        )
    try:
        ahead_count = int((ahead.stdout or "0").strip() or "0")
    except ValueError:
        ahead_count = 1
    if ahead_count > 0:
        return fail(
            f"auto_push: branch is still {ahead_count} commit(s) ahead of origin/{branch}",
            followup=(
                f"auto_push: origin/{branch} does not contain HEAD "
                f"({ahead_count} commit(s) ahead) after push. "
                "Do not force-push and do not push main. Fetch and push this branch again."
            ),
        )
    log(f"pushed origin/{branch}")
    return 0


def main() -> int:
    raw = sys.stdin.read()
    if raw.startswith("\ufeff"):
        raw = raw[1:]
    try:
        payload = json.loads(raw or "{}")
    except json.JSONDecodeError:
        payload = {}

    loop_count = int(payload.get("loop_count") or 0)
    status = str(payload.get("status") or "").lower()
    # An aborted stop must not be blocked back into the agent loop.
    if status == "aborted":
        log("skipped: agent aborted")
        emit()
        return 0

    if not (REPO / ".git").exists():
        log(f"skipped: not a git repo ({REPO})")
        emit()
        return 0

    inside = git("rev-parse", "--is-inside-work-tree")
    if inside.returncode != 0 or inside.stdout.strip() != "true":
        log("skipped: not inside a work tree")
        emit()
        return 0

    branch = current_branch()
    if branch is None:
        return refuse_protected("detached HEAD")
    if branch in PROTECTED:
        return refuse_protected(f"current branch is {branch}")

    git_dir = Path(git_ok("rev-parse", "--git-dir").strip())
    if not git_dir.is_absolute():
        git_dir = REPO / git_dir
    if in_progress_git_state(git_dir):
        return fail(
            "auto_push: merge/rebase in progress; nothing was pushed",
            followup=(
                "auto_push: a merge or rebase is in progress, so nothing was pushed. "
                "Finish or abort it on this cursor/* branch. Never push main."
            ),
        )

    added = git("add", "-A")
    if added.returncode != 0:
        detail = redact((added.stderr or added.stdout or "git add failed").strip())
        return fail(f"auto_push: git add failed\n{detail}")
    unstage_secrets()

    commit_failed = False
    has_staged = git("diff", "--cached", "--quiet")
    if has_staged.returncode != 0:
        commit = git("commit", "-m", "Auto-push remaining agent changes.")
        if commit.returncode != 0:
            commit_failed = True
            detail = redact((commit.stderr or commit.stdout or "commit failed").strip())
            log(detail or "commit failed")
        else:
            log("committed leftover changes")
    else:
        log("nothing new to commit")

    push_code = push_feature_branch(branch)
    if push_code != 0:
        return push_code
    if commit_failed:
        return fail(
            "auto_push: leftover changes were not committed",
            followup=(
                "auto_push: git commit of leftover changes failed. "
                "Inspect the index on this cursor/* branch and commit again. "
                "Never push main and never force-push."
            ),
        )

    followup = None
    if loop_count < 8:
        ci = snapshot_ci(allow_git_credential=False)
        state = str(ci.get("state") or "unknown")
        log(f"ci state={state}")
        if state in {"pending", "failure", "unknown"}:
            followup = CI_FOLLOWUP
    emit(followup)
    return 0


if __name__ == "__main__":
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8")
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001 — never look successful after a crash
        print(f"auto_push: {redact(str(exc))}", file=sys.stderr, flush=True)
        emit(f"auto_push failed: {redact(str(exc))}")
        raise SystemExit(2)
