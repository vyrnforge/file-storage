# Repository security verification evidence

Date: 2026-11-14. Scope: GitHub repository only; no runtime security claim.
Owner reports security settings applied. Independent readback below.

## Verified configuration
- Main at d3e8de48c39e48ab9469ad7f7553084121ab57a7; [security PR #2](https://github.com/vyrnforge/file-storage/pull/2) merged.
- [Ruleset 27266257](https://github.com/vyrnforge/file-storage/rules/27266257): active, default branch target, no bypass actors, deletion and non-fast-forward restrictions, PR required, conversation resolution, 0 approvals (solo), required ci via GitHub Actions integration 15368, strict up-to-date enabled.
- Repository public; squash only, merge/rebase disabled, auto-delete merged head branches enabled. Ruleset allows all three methods; repository settings restrict to squash. Align rule methods later.
- [PR #2 CI](https://github.com/vyrnforge/file-storage/actions/runs/43607099702) and [main CI](https://github.com/vyrnforge/file-storage/actions/runs/43607426779) completed successfully.

## Negative enforcement exercise
[PR #3](https://github.com/vyrnforge/file-storage/pull/3) used a synthetic prohibited-path marker only, no private data. Head e481e652d498ccbcc1c04a61680c8097a4b8a287. [Run 43608253971](https://github.com/vyrnforge/file-storage/actions/runs/43608253971) completed failure at foundation/hygiene step. GitHub REST PR readback returned mergeable=true (no conflicts) and mergeable_state=blocked. Required CI policy active; observed blocked state. PR closed without merge; marker removed on test branch at bf299f61ddd643a93020cb6653fd5d719eb001eb. No merge attempt, direct main push, force push or deletion attempted.

## Remaining evidence
- Secret scanning, push protection, Dependabot, private vulnerability reporting, Actions repository settings, collaborator/team/key inventory: not independently readable through connector. Owner reports settings applied; exact individual statuses not verified. Collect sanitized owner settings evidence.
- Direct push rejection, deletion/force-push rejection: rules verified, negative operations not exercised to avoid any main mutation. Independent reviewer remains unassigned; zero approvals is a temporary solo exception.
- License and public/private intent unresolved; legacy credential hygiene/private build evidence open.
- CI validates documentation and tracked-path hygiene only, not secret content or runtime security. Extend when dependencies/code are introduced.

Status: source controls implemented and merged; branch rules applied and read back; CI positive and negative merge state verified. Full security closure pending remaining evidence. No infrastructure or runtime changes. Architecture approval remains separate.

## Owner confirmation 2026-11-14
Owner explicitly confirmed available secret scanning, push protection, Dependabot and Actions restrictions configured; public repository and Apache-2.0 approved. Treat these security features as owner-confirmed, not independently verified. Prior active ruleset and negative CI evidence unchanged. Legacy credential rotation and independent reviewer remain open; no direct-push/destructive tests attempted.
