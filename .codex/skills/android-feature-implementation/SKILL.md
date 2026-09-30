---
name: android-feature-implementation
description: Implement complex Android behavior in ValerochkaGym when persistence, sync contracts, authorization, background execution, or concurrency need coordinated validation. Use for substantial features or explicit invocation; ordinary edits and explanation-only requests do not need this workflow.
---

# Android Feature Implementation

Deliver the requested behavior with the smallest useful workflow. Follow AGENTS.md for project
invariants, Git isolation, versioning, testing and communication. This skill does not authorize
publication or changing the user's product scope.

## Choose the work, not a fixed sequence

The main agent normally reads, plans, implements and tests the feature itself. Do not create a
research → planner → implementer → tester → reviewer chain. A task spanning several files, or
changing versionCode/versionName only, does not require additional agents or strict gates.

Read relevant architecture, design-system and analogous code once. Reuse approved behavior,
existing plans and trustworthy test results; do not replay old workflow stages from a handoff.
Read only applicable sections of [Android quality gates](references/android-quality-gates.md)
when the affected behavior needs them.

For a focused change, state scope and assumptions briefly, then implement. For a long task with
dependencies, maintain a concise plan and tracker in vibe/<slug>-plan.md and
vibe/<slug>-plan-track.md. Include observable acceptance, risky contracts, next steps and test
results. Reuse existing AC/T IDs; new IDs and a separate Feature Brief are optional.

## Delegate only useful independent work

This skill authorizes bounded subagents when they materially help delivery:

- An independent reviewer for a substantial migration, sync/ownership, authorization or concurrency
  change with concrete data-loss, access-control or correctness risks.
- One implementer for a genuinely independent slice while the main agent performs useful work.
- A researcher only for a specific unresolved question; a planner only for a complex dependency
  problem; a tester only for a distinct coverage audit or validation workload.

Use the configured role profiles without model overrides. Pass fork_turns: "none" with the exact
scope, affected files, relevant decisions, evidence and expected result. Do not ask each agent to
rediscover the whole project. Prefer at most two subagents at a time; extra agents require a
concrete independent workload, not just an available slot.

Each writer has exclusive files. One owner handles Room schema/migrations, and shared DI,
navigation and sync changes have explicit ownership. Tell writers they are not alone and must
preserve other edits. Serialize Gradle and arrange early stable compile checkpoints. Reviewers
stay read-only. Reuse an existing agent for related fixes instead of creating a new role handoff.

Use the current checkout unless isolation is required by AGENTS.md. Parallel agents alone do
not justify a worktree. For explicitly requested parallel isolated sessions, read
[worktree mode](references/worktree-mode.md). The parent owns Git operations.

## Implement and verify incrementally

Finish a coherent slice and compile changed interfaces early. Add meaningful regression tests at
the lowest reliable layer. For migration, durable formats, account ownership, permissions and
background execution, verify the specific failure and recovery invariants before accepting the
change; merely touching an adjacent file does not require every conditional gate.

Run targeted checks while implementing. Reuse passing results for unchanged code. After a stable
diff, inspect it directly; use independent review for the substantial risks described above.
Consolidate confirmed findings into one fix pass and recheck the affected scenarios. Do not
restart broad research or review for a local fix. A remaining critical defect must be fixed or
reported as a concrete blocker; do not silently accept it to meet an iteration limit.

Run the final full unit suite and debug assembly as specified in AGENTS.md. In a large request,
use targeted checks between internal stages, with final gates for the delivered integration or
separately delivered feature. Documentation-only changes need no Gradle or version bump.

Report delivered behavior, verification and material limitations concisely. Update an existing
tracker's current status rather than appending repetitive transcripts. For a handoff record only
scope, current files/branch, reliable checks, unresolved failures and the next concrete action.
