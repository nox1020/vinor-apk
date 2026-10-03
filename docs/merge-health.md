# Merge health

`main` accepts work only through a pull request. One CI/CD owner squash-merges one pull request at a time. Agents do not push `main` and do not merge.

## Merge procedure

1. Work on a `cursor/*` branch, push only that branch, and open a pull request. Never commit or push `main`.
2. The CI/CD agent is the only merger. Squash is the only merge method. Merge one pull request, then the next.
3. `behind`: update the branch from `main` and wait until CI is green. The approval stays valid; do not treat an update as a new review.
4. `dirty`: do not merge. Alert the author and let them resolve the conflict.
5. If the merge API returns 405 or 409, or `mergeable` is still null, retry up to 3 times. Stop if it is still not mergeable.
6. After the squash, verify the commit is on `main` (the pull request is merged and `main` contains that squash commit). A green merge response by itself is not enough.

The stop hook (`.cursor/hooks/auto_push.py`) fetches `origin` and pushes `HEAD` to `origin/<current branch>` with upstream set on the first push. It never force-pushes. On `main`, `master`, or a detached HEAD it does not touch the remote, prints `auto_push: refusing to push main; use a PR branch` on stderr, and exits non-zero.

## Monitor

`.github/workflows/merge-health.yml` runs on the cron `43 5 * * *` (05:43 UTC, 09:13 Asia/Tehran) and on `workflow_dispatch`. It does not build the app and it does not merge. Findings update one GitHub issue and the job still succeeds.

There is no production health check in this repository.

### Reason codes

| Code | Meaning |
| --- | --- |
| `pr_dirty` | Open non-draft PR into `main` whose `mergeable_state` is `dirty`, and that condition has lasted more than 4 hours. |
| `pr_behind` | Same, for `mergeable_state=behind`. |
| `pr_blocked` | Same, for `mergeable_state=blocked`. |
| `pr_unstable` | Same, for `mergeable_state=unstable`. |
| `check_missing` | A name from `REQUIRED_CHECKS` has no check run (or the run was skipped) on the PR head. |
| `check_failed` | That check's latest conclusion is `failure`, `cancelled`, `timed_out`, `startup_failure`, `action_required`, or `stale` (or the commit status is `failure` / `error`). |
| `direct_push` | A commit on `main` in the last 26 hours whose `GET /repos/{repo}/commits/{sha}/pulls` list is empty. |
| `main_ci_failed` | The latest completed run on `main` of an active workflow other than Merge health ended `failure`, `cancelled`, or `startup_failure`. |

Age for the `pr_*` codes and for the check grace is approximate. The monitor uses the head commit's committer timestamp, then the author timestamp, then the pull request `updated_at`. It does not record the moment `mergeable_state` flipped. A `pr_*` finding is emitted only when that timestamp is strictly older than 4 hours. If `mergeable` is null, the pull request is re-fetched up to 3 times, 10 seconds apart, and then left unclassified if it is still null.

`check_missing` and `check_failed` are suppressed until 30 minutes after that same head timestamp, so a commit that just landed is not flagged before CI starts.

`direct_push` uses the committer timestamp on `main` (author timestamp if the committer time is missing) and a 26-hour window so the daily run overlaps the previous day. `main_ci_failed` looks only at the latest completed run on `main` and ignores `.github/workflows/merge-health.yml`.

### Required checks

The workflow reads check names from the repository variable `MERGE_HEALTH_REQUIRED_CHECKS` into `REQUIRED_CHECKS`. When the variable is empty or unset, `check_missing` and `check_failed` are skipped. After the backend pull request merges, set the variable to `android-ci-ok` (the always-run summary check). This workflow does not add that job and does not edit `android.yml`.

### Issue

One issue titled `Merge health` carries the label `merge-health` (created if missing). The body is a findings table plus a hidden `<!-- fp:<sha256 of the sorted finding keys> -->`. No findings closes it. Findings create, reopen, or update it. Only when that fingerprint changes does the workflow un-assign and re-assign `nox1020`, so GitHub emits `issue-assigned`.

## Expected ruleset

GitHub should enforce this on `main` (this pull request does not create the ruleset):

- A pull request is required before merging.
- Direct pushes are blocked, including force-push.
- The only merge method is squash.
- Required status check: `android-ci-ok`, once the separate backend pull request adds that always-run job. Do not dismiss existing approvals when the branch is updated.

## Versions

Feature pull requests do not bump `version.txt`. `versionCode` and `versionName` come from CI (`GITHUB_RUN_NUMBER` with an offset once that Gradle change is on `main`). What still reads `version.txt` today, and must not be edited here, is `app/build.gradle` (`readVersionName` / `computeVersionCode`, then `max` with `GITHUB_RUN_NUMBER`) and `.github/workflows/android.yml` (push path filter, release path filter, and the APK/AAB file name). `ApkVersionUtils.kt` applies the same formula to a version string and does not open the file.
