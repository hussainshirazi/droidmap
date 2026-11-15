#!/usr/bin/env bash
# Puts the notes app (version 1 of its database, already on Google Play) in the run's workspace, committed, so that
# droidmap's diff and the hooks see what the agent changes.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cp -R "$here/notes/." .
git init -q
git add -A
git -c user.name=eval -c user.email=eval@example.com -c commit.gpgsign=false commit -qm "Notes 1.0, on Google Play"
