---
name: solace-docs
description: "Use when the user wants to look up, find, quote, check, or read a specific page of the Solace documentation (any docs.solace.com page), or asks what the official Solace docs say about a feature. Do NOT trigger to build, design, generate, or advise on implementing a Solace application or JCSMP code, even when answering would involve consulting documentation (use solace-application-development). Do NOT trigger for topic-hierarchy design or topic-naming questions (use solace-topic-best-practices)."
allowed-tools: Read Grep WebFetch
---

# Solace Docs (online lookup)

This skill locates a Solace documentation page and reads it live from docs.solace.com. It does not bundle the documentation. It finds the right page URL and fetches the current content on demand.

## How to look up a page

1. Narrow to the page URL. Grep the bundled sitemap (the `docs/INDEX.md` file in this skill's directory) for your topic or area to find the page's live Markdown URL:
   `grep -i <keyword> docs/INDEX.md`
   The sitemap lists every public documentation page as its live `https://docs.solace.com/<path>.md` URL, sorted by area, so a path keyword (an area name, an API name, a page slug) narrows quickly.
2. Fetch the page. WebFetch the matched `https://docs.solace.com/<path>.md` URL to read the current page as Markdown.
3. If you already hold a `docs.solace.com/<path>.htm` link, swap the `.htm` suffix for `.md` and WebFetch that. Every documentation page has a Markdown form at the same path.

Grep the sitemap to narrow first, then WebFetch a single page. Do not WebFetch blindly. Quote or summarize the fetched page; do not answer from memory.

## What this skill is not

Do not use this skill to design, build, generate, or advise on implementing a Solace application or JCSMP code, even when the answer would come from documentation. That is the solace-application-development skill. Topic-hierarchy design and topic-naming questions belong to the solace-topic-best-practices skill.
