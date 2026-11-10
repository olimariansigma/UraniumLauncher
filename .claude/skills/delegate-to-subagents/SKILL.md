---
name: delegate-to-subagents
description: Delegate easy, high-volume work to cheap subagents so the main session saves tokens without losing quality. Use this skill whenever a task involves browsing or searching the web, reading many pages or files, sweeping a codebase, gathering facts, summarizing long material, or running several independent lookups, even if the user never mentions subagents.
---

# Delegate to subagents

Raw web pages, search results and file dumps fill the main context fast and make every later step cost more. A subagent reads the bulk material in its own context and returns a short result.

## Delegate when the work is
- Browsing or searching the web (docs, prices, versions, news)
- Sweeping many files or logs
- Gathering facts that I will then reason over
- Summarizing long pages or docs
- Several independent lookups (run them in parallel, same turn)

## Do it myself when
- It's one quick lookup or one small file read
- It needs judgment, design decisions or the full conversation context
- It's writing or editing code that must fit what I already know
- It's irreversible or risky (deleting, deploying, spending, pushing)

Rule of thumb: delegate the reading, keep the thinking.

## How to delegate
1. Easy tasks (browsing, searching, simple extraction): model haiku (Haiku 5.5), effort low.
2. Slightly harder tasks (comparing sources, interpreting messy material): model sonnet (Sonnet 5.5), effort medium.
3. Never use bigger models or higher effort for delegated work. Set model and effort on the Agent tool call. Explore agent type is good for read-only sweeps.
4. Write a self-contained brief: goal, what's already known, what to skip, what "done" means.
5. Ask for a short return (e.g. under 150 words) with source URLs or file paths, and for anything unverified to be flagged.

## Protect quality
- Treat subagent output as a report to check. Spot-check key claims before relying on them.
- If a result is thin or contradictory, re-run with a sharper brief or step up one level (Haiku low to Sonnet medium).
- Never paste raw subagent output to me. Give me the conclusion with sources.
- The main session owns the final answer.
