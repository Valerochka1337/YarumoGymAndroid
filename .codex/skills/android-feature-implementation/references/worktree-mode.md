# Explicit worktree mode

Use this reference for explicitly requested parallel isolated sessions. For routine branch isolation, follow AGENTS.md; do not create worktrees merely to run subagents.

## Isolation model

- One root Codex session owns one checkout/worktree.
- Multiple subagents spawned by that root session share its checkout; worktrees do not isolate those subagents from each other.
- Parallel root sessions must use distinct worktrees. If they write code, give each a distinct branch or keep Codex-managed worktrees detached until handoff.
- Never check out the same branch in two worktrees. Never let two sessions own the same file set or the same Room schema migration.
- Keep one integration owner for shared choke points and final verification.

## Before starting

1. Run read-only checks: `git status --short`, `git branch --show-current`, `git rev-parse HEAD`, and `git worktree list --porcelain`.
2. Record the selected base commit, session purpose, exclusive file ownership, and integration owner.
3. Do not silently omit uncommitted user changes. A manual worktree starts from a commit, while a Codex-managed worktree can copy selected local changes. If the requested base is ambiguous or required changes exist only in the dirty checkout, ask the user which base to use.
4. Do not copy ignored secrets or machine-local files. If a Codex-managed worktree needs ignored setup files, use a narrowly scoped `.worktreeinclude` only with explicit user approval and never include signing keys or credentials.

## Creating or using a worktree

Prefer the Codex app's Worktree selector when the user is launching parallel sessions. It creates a managed detached-HEAD worktree from the selected branch and keeps that chat associated with the worktree.

If the user explicitly asks this session to create a manual worktree, resolve and validate the exact base, target directory, and unique branch name first. Follow the branch naming and isolation rules in AGENTS.md and use one directory per lane. Local isolation does not authorize integration; commits, pushes, merges, rebases, cherry-picks, and deletion still require their own explicit request when not already included.

After creation, verify with `git worktree list --porcelain`, `git -C <path> status --short --branch`, and `git -C <path> rev-parse HEAD`. Start the corresponding root Codex session from that worktree, not from the original checkout.

## During parallel sessions

- Keep research/review lanes read-only where possible.
- Give write lanes disjoint task IDs and exact file ownership. Do not broaden ownership without coordination.
- Do not run two Gradle builds in the same worktree. Separate worktrees may build independently, but each maintains its own generated outputs and may consume substantial disk/CPU.
- Do not merge partial work merely to share context. Exchange stable contracts, commit hashes when the user authorized commits, or concise evidence summaries.

## Handoff and cleanup

- Report worktree path, base commit, branch or detached state, changed files, validation results, and integration status.
- Do not automatically merge, cherry-pick, rebase, push, remove a worktree, or delete its branch.
- Before any user-authorized removal, verify the exact worktree path, clean/committed state, and whether its commits are reachable elsewhere. Prefer Codex Handoff when moving a managed worktree chat back to Local.

Primary references: [Codex worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees), [Git worktree](https://git-scm.com/docs/git-worktree).
