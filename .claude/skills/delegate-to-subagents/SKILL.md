---
name: delegate-to-subagents
description: Delegate easy, high-volume work to cheap subagents so the main session saves tokens without losing quality. Use this skill whenever a task involves browsing or searching the web, reading many pages or files, sweeping a codebase, gathering facts, summarizing long material, or running several independent lookups, even if the user never mentions subagents. Also use it in Claude Code whenever a step would dump lots of raw text into the main context.
---

# Delegate to subagents

The main session is expensive and its context is precious. Raw web pages, search results, and file dumps fill that context fast and make every later step cost more. A subagent reads the bulk material in its own context and hands back only a short result. Used well, this cuts tokens a lot and quality stays the same.

## Delegate when the work is

- **Browsing or searching the web**: looking up docs, prices, versions, news, comparisons.
- **Sweeping many files**: finding where something is used, listing patterns across a repo, reading logs.
- **Gathering facts** you will then reason over: the subagent collects, you decide.
- **Summarizing** long pages, docs, or transcripts.
- **Independent parallel lookups**: several unrelated questions can run at the same time in separate subagents.

## Do it yourself when

- The task is one quick lookup or a single small file read. Spinning up a subagent costs more than it saves.
- The work needs judgment, design decisions, or the full conversation context.
- You are writing or editing code that must fit tightly with what you already know. Delegate the research for it, not the writing.
- Safety, security, or irreversible actions are involved (deleting, deploying, spending money, pushing). Keep those in the main session.

Rule of thumb: delegate the reading, keep the thinking.

## How to delegate well

1. **Pick the cheapest setup that is good enough.**
   - **Easy tasks** (browsing, searching, simple extraction, listing files): Haiku 5.5 with effort `low`.
   - **Slightly harder tasks** (comparing sources, interpreting results, summarizing messy material): Sonnet 5.5 with effort `medium`.
   - Never use Opus, Fable, or Mythos for lookups, and do not go above `medium` effort for delegated work. If a task needs more than that, it belongs in the main session.
   In Claude Code, set `model` (`haiku` or `sonnet`) and `effort` (`low` or `medium`) on the Agent tool call. For read-only sweeps, the `Explore` agent type fits.
2. **Write a self-contained brief.** The subagent has none of your context. State the goal, what you already know, what to skip, and what counts as done. A vague brief gets a vague or wrong result.
3. **Ask for a compact return.** Specify the format and length up front, such as "answer in under 150 words, with source URLs and exact file paths". Ask for facts and evidence, not a narration of what it did.
4. **Run independent subagents in parallel**, in the same turn, rather than one after another.
5. **Ask for confidence and gaps.** Tell it to flag anything it could not verify so you do not build on a guess.

## Protect quality

- Treat subagent output as a report to check, not as truth. Spot-check anything important: open the cited source or file yourself before relying on a key claim.
- If a result looks thin, contradictory, or too confident, re-run with a sharper brief, or step up one level (Haiku low to Sonnet medium), instead of patching it yourself.
- Do not paste a subagent's raw output to the user. Read it, then give the user the conclusion in your own words, with sources.
- Never delegate the final answer. The main session owns correctness.

## Brief template

```
Goal: <one sentence>
Context: <only what the subagent needs>
Do: <specific searches or files to cover>
Skip: <what is already known or out of scope>
Return: <format, max length, must include sources/paths, flag unverified items>
```

## Example

User asks: "Which of these 4 libraries is best maintained?"
Good: spawn 4 Haiku 5.5 (low effort) subagents in parallel, one per library, each returning last release date, open issue count, and a link. Then compare the four short reports yourself.
Bad: browse all four sites in the main session and keep 40 pages of text in context.
