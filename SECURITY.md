# Security

droidmap reads source files and writes reports. It runs no code from the projects it reads and sends nothing anywhere; the only network access is downloading droidmap itself (the installers, the GitHub Action, the Claude Code plugin's launcher and the Gradle plugin), each checked against its published SHA-256.

## Reporting a vulnerability

Please report it privately through GitHub: **Security > Report a vulnerability** on this repository ([direct link](https://github.com/hussainshirazi/droidmap/security/advisories/new)), not in a public issue.

Only the latest release is supported with fixes.
