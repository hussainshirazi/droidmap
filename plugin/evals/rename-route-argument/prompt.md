---
description: Rename a navigation route and its argument; the ViewModel reads the argument by name.
tags: [navigation]
max_turns: 30
timeout_seconds: 900
allowed_tools: [Read, Glob, Grep, Edit, Write, Skill, mcp__plugin_droidmap_droidmap__droidmap_check, mcp__plugin_droidmap_droidmap__droidmap_impact, mcp__plugin_droidmap_droidmap__droidmap_diff, mcp__plugin_droidmap_droidmap__droidmap_explain, mcp__plugin_droidmap_droidmap__droidmap_find, mcp__plugin_droidmap_droidmap__droidmap_show]
---

In our notes app (Kotlin, Compose Navigation with string routes, Hilt), rename the note detail route from `note/{noteId}` to `notes/{id}`, to match our web URLs. There is no Gradle or Android SDK here, so don't try to build; just make the code change.
