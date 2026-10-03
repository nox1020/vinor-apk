#!/usr/bin/env python3
"""Daily merge-health monitor. Stdlib plus the ``gh`` CLI. No tokens printed.

PR mergeable_state age is approximate. We use the head commit's committer
timestamp, then its author timestamp, then the pull request ``updated_at``.
We do not observe the moment ``mergeable_state`` itself flipped. ``pr_dirty``,
``pr_behind``, ``pr_blocked``, and ``pr_unstable`` are reported only when that
timestamp is strictly older than four hours.

``check_missing`` / ``check_failed`` wait 30 minutes after the same head
commit timestamp. Names come from ``REQUIRED_CHECKS`` (repository variable
``MERGE_HEALTH_REQUIRED_CHECKS``). An empty value skips those two codes.

``direct_push`` is a commit on ``main`` whose committer (else author) time is
within the last 26 hours and whose ``GET /repos/{repo}/commits/{sha}/pulls``
list is empty.

``main_ci_failed`` is the latest completed run on ``main`` of each active
workflow except this monitor, when the conclusion is ``failure``,
``cancelled``, or ``startup_failure``.

The process exits 0 when findings exist. It exits non-zero only when the
GitHub API cannot be queried.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
from datetime import datetime, timedelta, timezone
from typing import Any, Callable

TITLE = "Merge health"
LABEL = "merge-health"
ASSIGNEE = "nox1020"
STALE_PR = timedelta(hours=4)
CHECK_GRACE = timedelta(minutes=30)
DIRECT_WINDOW = timedelta(hours=26)
PR_CODES = {
    "dirty": "pr_dirty",
    "behind": "pr_behind",
    "blocked": "pr_blocked",
    "unstable": "pr_unstable",
}
FAILED_CHECK = {
    "failure",
    "cancelled",
    "timed_out",
    "startup_failure",
    "action_required",
    "stale",
}
FAILED_RUN = {"failure", "cancelled", "startup_failure"}

Request = Callable[[str, str, dict | None], tuple[int, Any]]
Sleeper = Callable[[int], None]


class GhError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(redact(message))
        self.status = status


def redact(text: str) -> str:
    text = re.sub(r"https?://[^/\s@]+@", "https://[redacted]@", text)
    text = re.sub(
        r"\b(?:ghs_|github_pat_|ghp_|glpat-)[A-Za-z0-9_]+\b",
        "[redacted]",
        text,
    )
    return text


def parse_time(value: Any) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    text = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def parse_required(raw: str | None) -> list[str]:
    text = (raw or "").strip()
    if not text:
        return []
    names: list[str] = []
    for part in text.replace("\n", ",").split(","):
        name = part.strip()
        if name and name not in names:
            names.append(name)
    return names


def cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ").strip()


def fingerprint(findings: list[dict[str, str]]) -> str:
    keys = sorted(item["key"] for item in findings)
    return hashlib.sha256("\n".join(keys).encode("utf-8")).hexdigest()


def render_body(findings: list[dict[str, str]], now: datetime, required: list[str]) -> str:
    fp = fingerprint(findings)
    if required:
        checks = "Required checks: " + ", ".join(required) + "."
    else:
        checks = (
            "Required checks: skipped (repository variable "
            "MERGE_HEALTH_REQUIRED_CHECKS is empty). After the backend PR "
            "merges, set it to android-ci-ok."
        )
    lines = [
        "## Merge health",
        "",
        f"Scanned at {now.strftime('%Y-%m-%dT%H:%M:%SZ')}.",
        "",
        "PR age uses the head commit committer time, then the author time, "
        "then `updated_at`. It is not the instant `mergeable_state` changed. "
        "`pr_dirty`, `pr_behind`, `pr_blocked`, and `pr_unstable` need that "
        "age to be greater than 4 hours.",
        "",
        checks,
        "Check findings wait 30 minutes after the head commit time. "
        "`direct_push` is a main commit in the last 26 hours with no associated "
        "pull request. A skipped required check counts as `check_missing`.",
        "",
    ]
    if not findings:
        lines.append("No findings.")
        lines.append("")
    else:
        lines.append("| Reason | Target | Detail |")
        lines.append("| --- | --- | --- |")
        for item in sorted(findings, key=lambda row: row["key"]):
            lines.append(
                f"| {cell(item['reason'])} | {cell(item['target'])} | {cell(item['detail'])} |"
            )
        lines.append("")
    lines.append(f"<!-- fp:{fp} -->")
    lines.append("")
    return "\n".join(lines)


def _pages(request: Request, path: str) -> list[Any]:
    items: list[Any] = []
    for page in range(1, 21):
        join = "&" if "?" in path else "?"
        status, data = request("GET", f"{path}{join}per_page=100&page={page}", None)
        if status >= 300:
            raise GhError(status, f"GET {path} failed ({status})")
        if not isinstance(data, list):
            raise GhError(status, f"GET {path} did not return a list")
        items.extend(data)
        if len(data) < 100:
            break
    return items


def _object(request: Request, path: str) -> tuple[int, dict[str, Any]]:
    status, data = request("GET", path, None)
    if status >= 300:
        return status, {}
    if not isinstance(data, dict):
        return status, {}
    return status, data


def head_stamp(request: Request, repo: str, pr: dict[str, Any]) -> tuple[datetime | None, str]:
    updated = parse_time(pr.get("updated_at"))
    sha = str((pr.get("head") or {}).get("sha") or "")
    if not sha:
        return (updated, "updated_at") if updated else (None, "none")
    status, data = _object(request, f"/repos/{repo}/commits/{sha}")
    if status == 404 or not data:
        return (updated, "updated_at") if updated else (None, "none")
    if status >= 300:
        raise GhError(status, f"commit {sha} failed ({status})")
    commit = data.get("commit") or {}
    committer = parse_time((commit.get("committer") or {}).get("date"))
    if committer:
        return committer, "committer"
    author = parse_time((commit.get("author") or {}).get("date"))
    if author:
        return author, "author"
    if updated:
        return updated, "updated_at"
    return None, "none"


def load_pr(request: Request, sleep: Sleeper, repo: str, number: int) -> dict[str, Any]:
    path = f"/repos/{repo}/pulls/{number}"
    status, data = _object(request, path)
    if status >= 300 or not data:
        raise GhError(status, f"pull {number} failed ({status})")
    tries = 0
    while data.get("mergeable") is None and tries < 3:
        sleep(10)
        tries += 1
        status, data = _object(request, path)
        if status >= 300 or not data:
            raise GhError(status, f"pull {number} refetch failed ({status})")
    return data


def check_findings(
    request: Request,
    repo: str,
    number: int,
    sha: str,
    required: list[str],
) -> list[dict[str, str]]:
    runs: list[dict[str, Any]] = []
    for page in range(1, 11):
        status, data = _object(
            request,
            f"/repos/{repo}/commits/{sha}/check-runs?per_page=100&page={page}",
        )
        if status == 404:
            break
        if status >= 300:
            raise GhError(status, f"check-runs {sha} failed ({status})")
        batch = list(data.get("check_runs") or [])
        runs.extend(batch)
        if len(batch) < 100:
            break

    status_rows: list[dict[str, Any]] = []
    status_code, status_body = _object(request, f"/repos/{repo}/commits/{sha}/status")
    if status_code >= 300 and status_code != 404:
        raise GhError(status_code, f"status {sha} failed ({status_code})")
    status_rows = list(status_body.get("statuses") or [])

    found: list[dict[str, str]] = []
    for name in required:
        matched = [row for row in runs if row.get("name") == name]
        if matched:
            def sort_key(row: dict[str, Any]) -> datetime:
                return (
                    parse_time(row.get("completed_at"))
                    or parse_time(row.get("started_at"))
                    or datetime.min.replace(tzinfo=timezone.utc)
                )

            latest = max(matched, key=sort_key)
            conclusion = str(latest.get("conclusion") or "")
            state = str(latest.get("status") or "")
            if conclusion == "skipped":
                found.append(_check("check_missing", number, name, sha, "check run skipped"))
            elif conclusion in FAILED_CHECK:
                found.append(
                    _check("check_failed", number, name, sha, f"conclusion={conclusion}")
                )
            elif conclusion in {"", "null"} and state in {"queued", "in_progress", "pending", "waiting"}:
                continue
            continue

        contexts = [row for row in status_rows if row.get("context") == name]
        if not contexts:
            found.append(_check("check_missing", number, name, sha, "no check run or status"))
            continue
        context_state = str(contexts[0].get("state") or "")
        if context_state in {"failure", "error"}:
            found.append(_check("check_failed", number, name, sha, f"status={context_state}"))
        elif context_state == "pending":
            continue
    return found


def _check(reason: str, number: int, name: str, sha: str, detail: str) -> dict[str, str]:
    return {
        "key": f"{reason}:#{number}:{name}",
        "reason": reason,
        "target": f"#{number} {name}",
        "detail": f"{detail}; head {sha[:7]}",
    }


def direct_pushes(request: Request, repo: str, now: datetime) -> list[dict[str, str]]:
    since = (now - DIRECT_WINDOW).strftime("%Y-%m-%dT%H:%M:%SZ")
    commits = _pages(request, f"/repos/{repo}/commits?sha=main&since={urllib.parse.quote(since)}")
    found: list[dict[str, str]] = []
    for commit in commits:
        sha = str(commit.get("sha") or "")
        if not sha:
            continue
        meta = commit.get("commit") or {}
        when = parse_time((meta.get("committer") or {}).get("date")) or parse_time(
            (meta.get("author") or {}).get("date")
        )
        if when is not None and now - when > DIRECT_WINDOW:
            continue
        pulls = _pages(request, f"/repos/{repo}/commits/{sha}/pulls")
        if pulls:
            continue
        subject = str((meta.get("message") or "").splitlines()[0] if meta.get("message") else sha[:7])
        found.append(
            {
                "key": f"direct_push:{sha}",
                "reason": "direct_push",
                "target": sha[:7],
                "detail": f"no associated pull request; {subject}",
            }
        )
    return found


def main_ci(request: Request, repo: str) -> list[dict[str, str]]:
    workflows: list[dict[str, Any]] = []
    for page in range(1, 11):
        status, data = _object(request, f"/repos/{repo}/actions/workflows?per_page=100&page={page}")
        if status >= 300:
            raise GhError(status, f"workflows failed ({status})")
        batch = list(data.get("workflows") or [])
        workflows.extend(batch)
        if len(batch) < 100:
            break

    found: list[dict[str, str]] = []
    for workflow in workflows:
        path = str(workflow.get("path") or "")
        name = str(workflow.get("name") or path or workflow.get("id"))
        if path.endswith("/merge-health.yml") or name == TITLE:
            continue
        if workflow.get("state") not in {None, "active"}:
            continue
        workflow_id = workflow.get("id")
        if workflow_id is None:
            continue
        status, data = _object(
            request,
            f"/repos/{repo}/actions/workflows/{workflow_id}/runs"
            "?branch=main&status=completed&per_page=1",
        )
        if status >= 300:
            raise GhError(status, f"runs for {path or name} failed ({status})")
        runs = list(data.get("workflow_runs") or [])
        if not runs:
            continue
        conclusion = str(runs[0].get("conclusion") or "")
        if conclusion not in FAILED_RUN:
            continue
        run_id = runs[0].get("id")
        found.append(
            {
                "key": f"main_ci_failed:{path or name}",
                "reason": "main_ci_failed",
                "target": name,
                "detail": f"conclusion={conclusion}; run {run_id}",
            }
        )
    return found


def collect_findings(
    request: Request,
    sleep: Sleeper,
    now: datetime,
    repo: str,
    required_raw: str | None,
) -> list[dict[str, str]]:
    required = parse_required(required_raw)
    findings: list[dict[str, str]] = []
    for pr in _pages(request, f"/repos/{repo}/pulls?state=open"):
        if pr.get("draft"):
            continue
        base = str((pr.get("base") or {}).get("ref") or "")
        if base not in {"main", "master"}:
            continue
        number = int(pr["number"])
        detail = load_pr(request, sleep, repo, number)
        if detail.get("draft"):
            continue
        stamp, source = head_stamp(request, repo, detail)
        state = str(detail.get("mergeable_state") or "")
        code = PR_CODES.get(state)
        if code and stamp is not None and now - stamp > STALE_PR:
            sha = str((detail.get("head") or {}).get("sha") or "")
            findings.append(
                {
                    "key": f"{code}:#{number}",
                    "reason": code,
                    "target": f"#{number}",
                    "detail": (
                        f"mergeable_state={state}; head {stamp.strftime('%Y-%m-%dT%H:%M:%SZ')} "
                        f"({source}, >4h) {sha[:7]}"
                    ),
                }
            )
        sha = str((detail.get("head") or {}).get("sha") or "")
        if required and sha and stamp is not None and now - stamp > CHECK_GRACE:
            findings.extend(check_findings(request, repo, number, sha, required))
    findings.extend(direct_pushes(request, repo, now))
    findings.extend(main_ci(request, repo))
    return findings


def _fp_of(issue: dict[str, Any]) -> str | None:
    match = re.search(r"<!-- fp:([0-9a-f]{64}) -->", str(issue.get("body") or ""))
    return match.group(1) if match else None


def find_issue(request: Request, repo: str) -> dict[str, Any] | None:
    matches: list[dict[str, Any]] = []
    for issue in _pages(request, f"/repos/{repo}/issues?state=all"):
        if "pull_request" in issue:
            continue
        if issue.get("title") != TITLE:
            continue
        matches.append(issue)
    if not matches:
        return None
    matches.sort(key=lambda item: int(item.get("number") or 0))
    return matches[0]


def ensure_label(request: Request, repo: str, number: int | None) -> None:
    status, _data = request("GET", f"/repos/{repo}/labels/{LABEL}", None)
    if status == 404:
        created, _payload = request(
            "POST",
            f"/repos/{repo}/labels",
            {
                "name": LABEL,
                "color": "b60205",
                "description": "Opened by the merge health monitor",
            },
        )
        if created >= 300:
            raise GhError(created, f"create label failed ({created})")
    elif status >= 300:
        raise GhError(status, f"read label failed ({status})")
    if number is None:
        return
    labeled, _payload = request(
        "POST",
        f"/repos/{repo}/issues/{number}/labels",
        {"labels": [LABEL]},
    )
    if labeled >= 300:
        raise GhError(labeled, f"add label failed ({labeled})")


def reassign(request: Request, repo: str, number: int) -> None:
    removed, _data = request(
        "DELETE",
        f"/repos/{repo}/issues/{number}/assignees",
        {"assignees": [ASSIGNEE]},
    )
    if removed >= 300 and removed != 404:
        raise GhError(removed, f"unassign failed ({removed})")
    assigned, _data = request(
        "POST",
        f"/repos/{repo}/issues/{number}/assignees",
        {"assignees": [ASSIGNEE]},
    )
    if assigned >= 300:
        raise GhError(assigned, f"assign failed ({assigned})")


def sync_issue(
    request: Request,
    repo: str,
    body: str,
    findings: list[dict[str, str]],
) -> None:
    existing = find_issue(request, repo)
    fp = fingerprint(findings)
    if not findings:
        if existing is None:
            return
        if existing.get("state") == "closed" and _fp_of(existing) == fp:
            return
        number = int(existing["number"])
        status, _data = request(
            "PATCH",
            f"/repos/{repo}/issues/{number}",
            {"title": TITLE, "body": body, "state": "closed"},
        )
        if status >= 300:
            raise GhError(status, f"close issue failed ({status})")
        ensure_label(request, repo, number)
        return

    if existing is None:
        ensure_label(request, repo, None)
        status, created = request(
            "POST",
            f"/repos/{repo}/issues",
            {"title": TITLE, "body": body, "labels": [LABEL]},
        )
        if status >= 300 or not isinstance(created, dict):
            raise GhError(status, f"create issue failed ({status})")
        number = int(created["number"])
        reassign(request, repo, number)
        return

    number = int(existing["number"])
    changed = _fp_of(existing) != fp
    status, _data = request(
        "PATCH",
        f"/repos/{repo}/issues/{number}",
        {"title": TITLE, "body": body, "state": "open"},
    )
    if status >= 300:
        raise GhError(status, f"update issue failed ({status})")
    ensure_label(request, repo, number)
    if changed:
        reassign(request, repo, number)


def gh_request(method: str, path: str, payload: dict | None = None) -> tuple[int, Any]:
    resource = path.split("?", 1)[0].rstrip("/")
    accept = "application/vnd.github+json"
    if "/commits/" in resource and resource.endswith("/pulls"):
        accept = "application/vnd.github.mockingbird-preview+json"
    cmd = [
        "gh",
        "api",
        "--method",
        method,
        "-H",
        f"Accept: {accept}",
        "-H",
        "X-GitHub-Api-Version: 2022-11-28",
    ]
    cmd.append(path)
    input_text = None
    if payload is not None:
        input_text = json.dumps(payload)
        cmd.extend(["--input", "-"])
    proc = subprocess.run(
        cmd,
        input=input_text,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )
    body_text = (proc.stdout or "").strip()
    data: Any = None
    if body_text:
        try:
            data = json.loads(body_text)
        except json.JSONDecodeError:
            data = {"message": body_text[:300]}
    if proc.returncode == 0:
        return 200, data
    message = redact((proc.stderr or body_text or "gh api failed").strip())
    match = re.search(r"HTTP (\d+)", proc.stderr or "")
    status = int(match.group(1)) if match else 1
    if isinstance(data, dict) and data.get("message") and status == 1:
        lowered = str(data.get("message") or "")
        if "Not Found" in lowered:
            status = 404
    return status, data if data is not None else {"message": message}


def main() -> int:
    repo = (os.environ.get("REPO") or os.environ.get("GITHUB_REPOSITORY") or "").strip()
    if repo.count("/") != 1:
        print("merge-health: REPO is not set", file=sys.stderr, flush=True)
        return 1
    required_raw = os.environ.get("REQUIRED_CHECKS", "")
    now = datetime.now(timezone.utc)
    findings = collect_findings(gh_request, time.sleep, now, repo, required_raw)
    body = render_body(findings, now, parse_required(required_raw))
    sync_issue(gh_request, repo, body, findings)
    print(
        f"merge-health: findings={len(findings)} fingerprint={fingerprint(findings)}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    try:
        if hasattr(sys.stdout, "reconfigure"):
            sys.stdout.reconfigure(encoding="utf-8")
        if hasattr(sys.stderr, "reconfigure"):
            sys.stderr.reconfigure(encoding="utf-8")
        raise SystemExit(main())
    except GhError as exc:
        print(f"merge-health: {exc}", file=sys.stderr, flush=True)
        raise SystemExit(1)
    except Exception as exc:  # noqa: BLE001
        print(f"merge-health: {redact(str(exc))}", file=sys.stderr, flush=True)
        raise SystemExit(1)
