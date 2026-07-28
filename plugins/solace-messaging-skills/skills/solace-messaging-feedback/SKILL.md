---
name: solace-messaging-feedback
description: "Formats the current session into copy-paste-ready feedback about the Solace Agent Skills, routed by support-contract status: users with a Solace support contract get an email to Solace Support (support@solace.com) for bugs and a Solace Ideas portal submission for feature ideas, while users without a contract get a Solace Community post for both. Invoke whenever the user runs `/solace-messaging-feedback`, says things like 'I have feedback', 'this didn't work', 'this is confusing', 'I wish it did X', 'I want to report something', or otherwise signals dissatisfaction or an improvement idea about any Solace skill they just used (for example solace-application-development JCSMP code generation, or the topic-architecture lookup) or about the code those skills generated, even when the word 'feedback' isn't spoken. This skill is a formatter, not a transport: it produces the text in chat and never sends, posts, drafts, files, or writes."
allowed-tools: Read
---

# solace-messaging-feedback

Turn the current session into copy-paste-ready feedback and route it to the right Solace channel. Support-contract holders get a Solace Support email for bugs and an Ideas portal submission for feature ideas; users without a contract get a Solace Community post for both. The skill produces text in chat and performs no transport of any kind (see Guardrails). The user reviews the output, then copies it into their own mail client or browser and chooses when to send or post.

## When this skill applies

- The user invokes `/solace-messaging-feedback`.
- The user signals dissatisfaction or an improvement idea: *"I have feedback"*, *"this didn't work"*, *"this is confusing"*, *"I wish it did X"*, *"I want to report something"*.
- The user asks how to report a bug or share a suggestion about the Solace Agent Skills or the code they generated.

## Routing

The destination depends on two things: the shape of the feedback, and whether the user is a paying Solace customer with a support contract. Classify the shape first. **Feature-shaped** feedback is *"I wish it did X"*, enhancement ideas, and requests for new capability. **Bug-shaped** feedback is defects, failures, and confusion reports. Then ask the contract question before drafting anything.

For bug-shaped feedback:

> Do you have a support contract with Solace? If yes, I can draft an email to Solace Support for you to review and send. If not, I'll format this as a post for the Solace Community (https://community.solace.com/) instead.

For feature-shaped feedback:

> Do you have a support contract with Solace? If yes, I'll format this as a submission for the Solace Ideas portal (https://ideas.solace.com/ideas). If not, I'll format it as a post for the Solace Community (https://community.solace.com/) instead.

Route on the answer:

| Feedback shape | Support contract confirmed | No contract, or unsure |
|---|---|---|
| Bug-shaped (including confusion reports) | Support email | Community post |
| Feature-shaped | Ideas portal submission | Community post |

Users without a support contract use the Solace Community for both bug reports and feature ideas. Never draft a Support email or an Ideas portal submission for them; Solace Support redirects reports from users without a contract to the community, so routing there directly saves the user that round trip.

The contract question is a mandatory checkpoint: never emit a Support email unless the user has confirmed, in this session, that they hold a support contract and want the email drafted. Certainty in the user's phrasing ("just write the support email") does not waive the checkpoint. Ask anyway.

## Output

Emit a single fenced block containing plain text (no markdown bold or bullets inside the block) so it pastes cleanly into a mail client or a web form. All three destinations share the field rules, environment capture, and minimization passes below; only the wrapper differs. Reviewers on every channel triage many reports, so the labeled sections speed reading.

### Support email (bug-shaped, support contract confirmed)

```text
To: support@solace.com
Subject: Solace Agent Skills feedback: <short summary>

Hi Solace Support,

I'm sharing feedback about the Solace Agent Skills.

Actual behavior
<what happened>

Impact
<why it matters, what it blocked or made awkward>

Expected behavior
<what was expected, or what the user wants to see>

Steps to reproduce
1. <step>
2. <step>

Notes
<anything else>

Environment
- Skill / API: <e.g. solace-application-development / JCSMP | unknown>
- Plugin version: <version | unknown>
- Model: <model id | unknown>

Thanks,
<your name>
```

After the block, on a new line:

> Copy this into an email to Solace Support (support@solace.com), review and edit it, then send.

### Community post (no support contract: bugs, confusion reports, and feature ideas)

```text
Title: Solace Agent Skills feedback: <short summary>

I'm sharing feedback about the Solace Agent Skills.

Actual behavior
<what happened>

Impact
<why it matters, what it blocked or made awkward>

Expected behavior
<what was expected, or what the user wants to see>

Steps to reproduce
1. <step>
2. <step>

Notes
<anything else>

Environment
- Skill / API: <e.g. solace-application-development / JCSMP | unknown>
- Plugin version: <version | unknown>
- Model: <model id | unknown>
```

After the block, on a new line:

> Review and edit this, then post it on the Solace Community: https://community.solace.com/

### Ideas portal submission (feature-shaped, support contract confirmed)

The labeled sections mirror the Ideas portal's own form fields, so each section pastes into the matching field.

```text
Title: <short summary of the idea>

What is the challenge?
<the current state and the friction it causes>

What is the impact?
<why it matters, what it blocked or made awkward>

What is the workaround?
<how the user gets by today, or None>

Describe your idea
<what the user wants to see>

Environment
- Skill / API: <e.g. solace-application-development / JCSMP | unknown>
- Plugin version: <version | unknown>
- Model: <model id | unknown>
```

After the block, on a new line:

> Review and edit this, then submit it on the Solace Ideas portal (https://ideas.solace.com/ideas). Each section maps to a portal form field; fold the Environment lines into "Describe your idea".

### Field rules

- **Subject / Title** summarizes the report in a few words. Keep it specific ("guaranteed publisher sample omits reconnect config") over generic ("bug in skill").
- **Actual behavior / Expected behavior** cover bug-shaped *and* feature-shaped feedback. "I wish it did X" goes in `Expected behavior` with the current state in `Actual behavior`. In the Ideas portal template the same content splits across `What is the challenge?` (the current state) and `Describe your idea` (what the user wants).
- **Impact** (rendered as `What is the impact?` on the Ideas portal) is omitted when the user gave no signal of cost or friction. Don't invent severity language.
- **What is the workaround?** (Ideas portal only) is how the user copes today, taken from the session. Use `None` when the session shows no workaround; don't invent one.
- **Steps to reproduce** is populated from the prompts and skill invocations of the current session when the feedback is bug-shaped. Omit entirely for feature requests and confusion reports, where repro steps don't apply.
- **Notes** is omitted when empty. Resist padding.
- **Signature** (Support email only) stays the literal placeholder `<your name>`. Never infer or fill in the sender's name from git config, the environment, or the session. The user types their own name when they review the email before sending.

Omit any field the session doesn't support. Don't fabricate content to fill a slot.

## Environment capture

Capture environment metadata best-effort. Any field that can't be determined renders as `unknown`. The skill never errors on env capture. `unknown` is always a valid value.

- **Skill / API**: which skill the user was working with (for example `solace-application-development`, or the specific API such as JCSMP) and, when relevant, `solace-topic-best-practices`. Fall back to `unknown` if unclear.
- **Plugin version**: read the `version` field from the plugin manifest at `.claude-plugin/plugin.json` under the plugin root, which is two directories above this skill (resolve `../../.claude-plugin/plugin.json` from this skill's base directory). This works both when the plugin is installed and in a development checkout of this repo, where the plugin root is `plugins/solace-messaging-skills/`. Fall back to `unknown` if unreadable or missing.
- **Model**: the active Claude model name or ID is typically surfaced to the assistant at runtime (for example `claude-opus-4-8`). Fall back to `unknown` if not surfaced.

Future env fields are added the same way: attempt, fall back to `unknown`, never block.

## Minimization

Before showing the draft to the user, run two passes over the drafted text. They catch different failures and are both required.

### Pass 1: load-bearing test

For every concrete value, name, or phrase in the draft, ask: *does removing this prevent the reader from understanding the problem?*

- **No** → delete the whole phrase. Not paraphrase. Not genericize. Delete.
- **Yes** → keep it, and apply the placeholderization rule below if the surviving string identifies a specific individual, organization, internal system, or secret.

The trap is paraphrase. Don't replace deleted content with generic descriptors like *"for a configured broker"* or *"the application"*. That is the same noise the deletion was meant to remove, just reworded. If the generic statement also doesn't carry the bug, it should also be removed.

Pass 1 typically deletes broker hostnames, VPN names, message VPN and client usernames, queue and topic names, file paths, account ids, configuration dumps, and descriptions of which user or environment ran the session, unless the bug *is* about one of those.

### Pass 2: tighten the writing

Strip clutter from what survives Pass 1: throat-clearing ("it should be noted that…", "interestingly…"), hedges ("possibly", "perhaps"), unmotivated adverbs ("very", "really"), redundancies, and passive voice where active is shorter. Cut anything that doesn't carry information.

### Placeholderization

For strings that survive Pass 1: if the string, or a substring inside a compound identifier, identifies a specific *individual*, *organization*, *internal system*, or *secret*, substitute a self-describing placeholder named after what the thing is. Generate the placeholder from the property being protected, not from a fixed list.

This applies inside compound identifiers, which is where category-based scrubs miss. `acme-dev` becomes `<customer>-dev`; `ACME_ORDERS_VPN` becomes `<CUSTOMER>_ORDERS_VPN`. Match the case of the original. The placeholder names the property; the reader infers the protection.

### Worked example

Drafted (before minimization):

> "After I asked the solace-application-development skill to build a JCSMP publisher for the acme-dev broker at tcps://mr-abc123.messaging.solace.cloud:55443, the generated `SolaceConnectionConfig` hard-coded the host and the acme-orders VPN name instead of reading them from `config.json`, which very much forced a manual cleanup before I could commit."

After both passes:

> "The solace-application-development skill generated a `SolaceConnectionConfig` that hard-coded the broker host and the VPN name instead of reading them from `config.json`, forcing a manual cleanup before commit."

What changed and why:

- *"for the acme-dev broker"* and the `tcps://…` host: deleted in Pass 1. The broker identity and host don't carry the bug.
- *"acme-orders"*: deleted in Pass 1. The VPN identity isn't load-bearing; that it was hard-coded is. Had the value survived Pass 1, it would be placeholdered as `<customer>-orders`.
- *"very much"*: deleted in Pass 2. Unmotivated intensifier.
- The `config.json`-versus-hard-code contrast: kept. That **is** the bug.

### Deletion audit

Pass 1 deletions are invisible in the finished draft, so the user can't catch over-deletion by reading it. After the copy instruction that follows the output block, add one line naming the categories Pass 1 removed, for example: `Removed during minimization: broker host, VPN name, 2 queue names. Tell me to restore anything needed to reproduce.` Omit the line when nothing was removed.

## Empty session

When invoked with no prior activity, the skill doesn't emit an empty draft. Ask the user what they want to share (what happened, what they expected, what they'd like to see), then route and format their response as described above.

## Guardrails

- **Formatter, not transport.** No issue creation, email sending, draft creation, community or portal posting, API calls, or file writes. The output stays in chat for the user to copy.
- **Confirm before drafting a Support email.** The support-contract question in Routing is a mandatory checkpoint. Never draft the Support email until the user confirms they hold a support contract and want the email drafted.
- **User-visible before send.** Always present the full draft so the user can review and edit before pasting, sending, or posting.
- **No fabrication.** Omit fields the session doesn't support rather than guessing.
- **Heuristic minimization.** Both passes are best-effort. The user is the final filter before sending, and the deletion audit line makes removals visible to that filter.
