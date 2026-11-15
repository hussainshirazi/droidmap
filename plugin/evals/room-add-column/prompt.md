---
description: Add a column to a Room entity of an app whose users already have data.
tags: [room]
max_turns: 40
timeout_seconds: 900
allowed_tools: [Read, Glob, Grep, Edit, Write, Skill, mcp__plugin_droidmap_droidmap__droidmap_check, mcp__plugin_droidmap_droidmap__droidmap_impact, mcp__plugin_droidmap_droidmap__droidmap_diff, mcp__plugin_droidmap_droidmap__droidmap_explain, mcp__plugin_droidmap_droidmap__droidmap_find, mcp__plugin_droidmap_droidmap__droidmap_show]
---

This is our notes app (Kotlin, Room, Hilt, Compose). Users want to pin notes: add a `pinned` flag to `Note`, and list pinned notes first in `NoteDao.observeAll()`. Version 1 is on Google Play and users have notes saved. There is no Gradle or Android SDK here, so don't try to build; just make the code change.
