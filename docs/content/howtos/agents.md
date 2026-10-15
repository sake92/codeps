---
layout: howto.html
title: Use codeps with an AI agent
description: Install the Codeps skill and ask your agent to find dependency problems and quick wins
---

# Use codeps with an AI agent

## Install the skill

Install the [Codeps skill](https://github.com/sake92/skills/tree/main/skills/codeps)
from your project directory:

```shell
npx skills add sake92/skills --skill codeps
```

Select your coding agent in the installer. Add `-g` to make the skill available
across your projects. See the
[skills installer documentation](https://github.com/vercel-labs/skills#readme)
for supported agents and installation options.

## Ask for an analysis

Open your project in the coding agent and give it a prompt such as:

> Use the codeps skill to analyze this codebase. Find dependency cycles, the
> messiest parts, and quick wins for simplification. Rank the findings by likely
> impact and effort, with concrete files or packages and evidence for each.
> Analyze and recommend changes first.

The skill guides the agent through choosing dependency inputs, building the
project, running codeps, and inspecting relevant findings. It includes a runner
for the supported CLI version. If your project needs input setup or the build
fails, the agent should explain what is missing and which conclusions it cannot
verify.

## Follow up on a finding

Choose a recommendation and ask the agent to implement it:

> Implement the smallest change that breaks the cycle you identified. Rebuild,
> run the relevant tests, and use codeps to verify the cycle is gone. Show the
> before and after evidence.

For manual analysis commands, see the [CLI reference](/reference/cli.html) and
[package triage tutorial](/tutorials/package-triage.html).
