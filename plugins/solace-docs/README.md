# solace-docs

A plugin with a single skill, solace-docs, that looks up Solace documentation online. It locates the right docs.solace.com page from a bundled sitemap and reads the current page on demand as Markdown, so answers quote the live documentation instead of model memory.

## How it works

The skill bundles no documentation content. It ships a sitemap (`skills/solace-docs/docs/INDEX.md`) that lists every public documentation page as its live Markdown URL. To answer a lookup, the skill greps the sitemap to narrow to a single page URL, then fetches that page with WebFetch and quotes or summarizes the current content. A known `.htm` documentation link can also be read directly by swapping its suffix for `.md`, since every page has a Markdown form at the same path.

## What it is not

This skill does not design, build, or generate Solace applications or JCSMP code. That is the solace-application-development skill in the solace-messaging-skills plugin.

## Requirements

The agent needs the WebFetch tool and network access to docs.solace.com. Nothing else is required.

## Trigger evals

The plugin ships a trigger eval corpus under `evals/`. See the [trigger evals README](evals/README.md) for the corpus format and how to run the evals locally.
