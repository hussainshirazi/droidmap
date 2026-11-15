---
description: Add a Compose screen with an injected ViewModel to the navigation graph.
tags: [hilt, navigation]
max_turns: 30
timeout_seconds: 900
allowed_tools: [Read, Glob, Grep, Edit, Write, Skill, mcp__plugin_droidmap_droidmap__droidmap_check, mcp__plugin_droidmap_droidmap__droidmap_impact, mcp__plugin_droidmap_droidmap__droidmap_diff, mcp__plugin_droidmap_droidmap__droidmap_explain, mcp__plugin_droidmap_droidmap__droidmap_find, mcp__plugin_droidmap_droidmap__droidmap_show]
---

In our notes app (Kotlin, Compose, Hilt), add a Stats screen at the route `stats`, opened from the notes list, that shows how many notes there are. Give it its own `StatsViewModel` that gets `NoteRepository` injected. There is no Gradle or Android SDK here, so don't try to build; just make the code change.
