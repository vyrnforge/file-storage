# Repository security baseline

Status: source controls merged; active branch ruleset and CI gate verified. Remaining administrative settings and destructive-operation enforcement tests open. See [verification evidence](REPOSITORY_SECURITY_EVIDENCE.md). Based on VyrnRail soloBootstrap. No runtime implementation or cloud access.

## Owner and scope
Repository: vyrnforge/file-storage. Default branch main. Primary maintainer @draglide; backup unassigned. Visibility currently public; owner must confirm public vs private and license. Public history may persist even if visibility changes. Never commit legacy archive, genuine credentials or real tenant files. Review/rotate any old credentials privately.

## Source controls
- CODEOWNERS identifies maintainer; no required code-owner approval in solo mode.
- Stable Actions job ci, PR/main/manual triggers, read-only token, pinned checkout, no persisted credentials, timeout, no deployment or secrets.
- Local parity: make ci (foundation inventory and tracked-path hygiene only). Expand to build, tests, scans and dependency review as real dependencies arrive.
- Path hygiene is not content scanning; use GitHub secret scanning/push protection and review.

## Apply hosted settings (owner)
1. Settings > Rules > Rulesets > New branch ruleset, name Protect main, Active, target default branch. No bypass actors. Require pull request before merging, zero required approvals while solo; require conversation resolution; restrict deletions and block force pushes. Require status check ci (GitHub Actions source) after first successful run, require branch up to date. Do not restrict updates to main outright (would block normal PR merges). Add one independent approval and code-owner review when a second eligible maintainer exists.
2. Settings > General > Pull Requests: squash merge only, automatic deletion of head branches. Keep merge/rebase off. Do not require signed commits until signer/workflow compatibility is verified.
3. Settings > Actions > General: read-only workflow permissions; disable Actions creating/approving PRs; review allowed actions and enforce SHA pins where supported. Require approval for all outside collaborators' fork workflows. Never use pull_request_target to execute untrusted PR code.
4. Settings > Security / Code security and analysis: enable secret scanning, push protection, Dependabot alerts/security updates and private vulnerability reporting as available. No raw secret values in issues. Enable dependency review/CodeQL when supported build and dependencies exist; evaluate actual plan/visibility availability.
5. Review repository collaborators/teams, org default access, deploy keys, installed apps, tokens, org 2FA and least privilege. Do not remove access blindly; owner confirms authorized inventory. No cloud credentials in CI; future deployment via scoped short-lived identity and protected environments.

## Evidence and closure
Record settings screenshots or readback (no secrets), ruleset ID, passing ci run, failing PR check blocked from merging, corrected check passing, direct-push rejection from non-bypass identity and deletion/force-push restrictions. Use harmless temporary test branch/PR; never force push or delete main to test. Do not claim enforced controls merely from committed YAML. Hosted governance remains partially verified; see evidence record for outstanding controls. Branch protection API may return 403 with current connector; owner settings UI is required if so.

## Implementation gate
Review and merge security PR; apply rules; confirm positive/negative CI and branch enforcement; resolve visibility/license and legacy credential hygiene; approve architecture and roadmap separately before coding.
