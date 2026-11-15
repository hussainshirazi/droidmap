# intent-extra-missing

Default: **warning**. An activity, service or receiver reads an extra of the Intent that started it (`intent.getStringExtra("itemId")`, `intent.extras?.getLong(...)`, `IntentCompat.getParcelableExtra(intent, ...)`) under a key that none of the Intents the app sends to it put.

droidmap checks a component only when it sees every Intent sent to it and the keys each one puts: Intents built in place (`Intent(this, X::class.java).putExtra(...)`, `.apply { putExtra(...) }`, or a local variable) and passed straight to `startActivity`, `startService`, `sendBroadcast`, `PendingIntent.get...` and the like. A component with an intent filter, `android:exported="true"`, an activity-alias, a navigation destination, or a class handed to other code (a helper, a reified `startActivity<X>()`) can be started in ways droidmap does not see, and is not checked. Only constant keys are compared, and keys a test puts are not reported.

## Why it matters

A key nobody puts is never there: the value is always null (or the default of `getIntExtra`). The screen shows nothing, or `checkNotNull(...)` crashes it, and the two strings live in different files that nothing compares.

## How to fix

Use one key on both sides, ideally one constant next to the component:

```kotlin
class DetailActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        fun intent(context: Context, id: Long) = Intent(context, DetailActivity::class.java).putExtra(EXTRA_ITEM_ID, id)
    }
}
```
