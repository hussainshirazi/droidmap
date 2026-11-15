# notification-channel-missing

Default: **warning**. A notification is built for a channel id (`NotificationCompat.Builder(context, "reminders")`, `setChannelId(...)`) that the app never creates (`NotificationChannel("reminders", ...)`, `NotificationChannelCompat.Builder("reminders", ...)`).

An id can be a constant, or a parameter of the function around it when every call of that function passes a constant (`fun createChannel(id: String)` called with `"general"`). droidmap checks only when it knows the id of every channel the app creates.

## Why it matters

From Android 8, a notification posted to a channel that does not exist is dropped: the system logs "No Channel found" and shows nothing. If a foreground service shows it, `startForeground` crashes the app ("Bad notification for startForeground: invalid channel"). A channel created on the developer's phone by an older build hides the problem.

## How to fix

Create every channel, with the same id, before posting to it (once, at app start):

```kotlin
const val REMINDERS = "reminders"

NotificationManagerCompat.from(context).createNotificationChannel(
    NotificationChannelCompat.Builder(REMINDERS, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName("Reminders").build()
)
NotificationCompat.Builder(context, REMINDERS)
```
