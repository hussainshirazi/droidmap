package droidmap.extract

import droidmap.Call
import droidmap.Code
import droidmap.Fn
import droidmap.Graph
import droidmap.arg
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

private val NOTIFICATION_BUILDERS = setOf("NotificationCompat", "Notification")

/**
 * Notification channels: the ids notifications are built for (`NotificationCompat.Builder(context, CHANNEL)`, `setChannelId(CHANNEL)`)
 * and the ids of the channels the app creates (`NotificationChannel(CHANNEL, ...)`, `NotificationChannelCompat.Builder(CHANNEL, ...)`).
 * From Android 8, a notification for a channel that does not exist is not shown. An id can come through one parameter of the
 * function around it (`fun createChannel(id: String)`), from that function's calls. Recorded as meta notification_channel_missing,
 * only when droidmap knows the id of every channel the app creates.
 */
class NotificationChannels(private val code: Code, private val g: Graph) {
    private val values = Values(code)
    private val app = code.sources.filter { !it.isTest }

    fun run() {
        val created = HashSet<String>()
        val used = LinkedHashMap<String, MutableList<Call>>()
        for (s in app) for (c in s.calls) {
            when {
                creates(c) -> created += ids(c.args.arg("id", 0)?.expr, c) ?: return // a channel whose id is not known: cannot tell
                builds(c) -> ids(c.args.arg("channelId", 1)?.expr, c)?.forEach { used.getOrPut(it) { ArrayList() }.add(c) }
                c.name == "setChannelId" -> ids(c.args.firstOrNull()?.expr, c)?.forEach { used.getOrPut(it) { ArrayList() }.add(c) }
            }
        }
        for ((id, calls) in used) {
            if (id in created) continue
            val first = calls.minWith(compareBy({ it.src.path }, { it.line }))
            g.addMeta("notification_channel_missing", linkedMapOf("channel" to id, "created" to created.sorted(), "uses" to calls.size,
                "file" to first.src.path, "line" to first.line))
        }
    }

    private fun creates(c: Call) = (c.name == "NotificationChannel" && (c.receiver == null || c.isNew)) ||
        (c.name == "Builder" && c.receiver?.substringAfterLast('.') == "NotificationChannelCompat")

    private fun builds(c: Call) = c.name == "Builder" && c.receiver?.substringAfterLast('.') in NOTIFICATION_BUILDERS && c.args.size >= 2

    /** The constant ids [e] can be: a constant, or a parameter of the function around [at] that its calls pass constants for. Null when not known. */
    private fun ids(e: PsiElement?, at: Call): Set<String>? {
        values.constant(e)?.let { return setOf(it) }
        val fn = at.fn ?: return null
        val name = (e as? KtNameReferenceExpression)?.getReferencedName() ?: (e as? PsiReferenceExpression)?.takeIf { it.qualifierExpression == null }?.referenceName
        val i = fn.params.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return null
        val out = HashSet<String>()
        for (c in callsOf(fn)) out += values.constant(c.args.arg(name, i)?.expr) ?: return null
        return out.ifEmpty { null }
    }

    /** The calls to [fn] droidmap can tell are to it: by name, without a receiver (or on `this`). */
    private fun callsOf(fn: Fn): List<Call> = app.flatMap { s ->
        s.calls.filter { c -> c.name == fn.name && (c.receiver == null || c.receiver == "this") && code.index.resolveMember(c.name, s, c.psi) === fn }
    }
}
