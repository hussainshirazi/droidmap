---
type: regex
target: trace
match: not_contains
pattern: 'viewModel<StatsViewModel>\(|StatsViewModel(\s|\\n)*=(\s|\\n)*viewModel\('
---

The screen gets StatsViewModel with hiltViewModel(), not viewModel(), which cannot call its @Inject constructor inside a navigation destination and crashes.
