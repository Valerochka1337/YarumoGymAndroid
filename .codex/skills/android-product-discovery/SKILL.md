---
name: android-product-discovery
description: Define substantial ValerochkaGym product behavior when the user requests product discovery, competitor research, a comparison of alternatives, or an agreed feature brief. Simple opinions, code explanations and ordinary UI discussions should be answered directly without a discovery workflow.
---

# Android Product Discovery

Match depth to the user's decision. Preserve the requested scope and distinguish current behavior,
recommendations and unvalidated assumptions. Do not turn a small idea into a broader feature.
This skill does not authorize implementation, version bumps or publication.

## Focus the decision

Read relevant code and applicable sections of architecture/design guidance. Reuse established
product decisions and previously read instructions. Do not survey all screens, persistence and
sync unless the proposed behavior actually affects them.

State the user problem, current behavior and recommendation. Compare only materially different
options that help the decision; a broader alternative is optional. Ask only when an unresolved
choice changes behavior, data preservation or scope. Give a useful provisional answer in the
current turn rather than waiting for a formal discovery process to finish.

## Research when it informs the answer

Use external research when requested or when the recommendation relies on external, uncertain or
current facts; obey the applicable browsing requirements. Local code analysis alone does not
require competitor research. Read [research playbook](references/research-playbook.md) for a
substantial market/domain investigation, not for every question or single-source verification.
Keep research bounded to the decision and stop when evidence answers the relevant uncertainty.
Cite external claims, distinguish capability from effectiveness, and do not invent telemetry.

## Record only the requested deliverable

For discussion, answer in chat. For an explicitly requested brief, use
[product brief contract](references/product-brief-contract.md) and write a concise Russian
vibe/<slug>-product.md, or the user's requested location. Include only relevant sections.
Mark provisional decisions as draft; claim agreement only after explicit acceptance.
If the user requests an issue, prepare the issue itself without also creating a local brief and
implementation plan. Publish only with authorization for that external action.

Do not require a second approval just to finish the requested analysis or draft. Product approval
does not authorize code changes; continue implementation only when the user has requested it.
