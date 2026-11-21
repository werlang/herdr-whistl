package io.github.werlang.whistl.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.werlang.whistl.MainActivity
import io.github.werlang.whistl.R
import io.github.werlang.whistl.domain.AgentStatus
import io.github.werlang.whistl.domain.FinishedAgents
import io.github.werlang.whistl.domain.LimitAlert
import io.github.werlang.whistl.domain.LimitAlerts
import io.github.werlang.whistl.data.isWaitingForUser
import io.github.werlang.whistl.ui.agents.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Watches the agents and their limits while the app is not on screen (0.7.0).
 *
 * The agents only live inside an SSH session the app holds, so a notification after the app is backgrounded needs
 * something that keeps the process running: this service, in the foreground, with its own quiet notification. It posts
 * one notification per agent that stops working, and none for an agent that is waiting for the user — that one has not
 * finished, it is asking. It also announces a provider window that reaches 90% used (0.7.9), once per reset cycle.
 */
class AgentWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var container: io.github.werlang.whistl.AppContainer

    override fun onCreate() {
        super.onCreate()
        container = (application as io.github.werlang.whistl.WhistlApplication).container
        createChannels()
        startForeground(WATCH_ID, watchingNotification())
        val statuses = HashMap<String, AgentStatus?>()
        scope.launch {
            var first = true
            container.agents.agents.collect { agents ->
                if (first) {
                    // The first list is the state as it is, not a change to report.
                    statuses.putAll(FinishedAgents.statuses(agents))
                    first = false
                    return@collect
                }
                val finished = FinishedAgents.between(statuses, agents)
                statuses.clear()
                statuses.putAll(FinishedAgents.statuses(agents))
                finished.forEach(::notifyFinished)
            }
        }
        scope.launch {
            var alerted = emptySet<String>()
            container.limits.reports().collect { reports ->
                val (alerts, next) = LimitAlerts.between(alerted, reports)
                alerted = next
                alerts.forEach(::notifyLimit)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, getString(R.string.notify_channel_watch), NotificationManager.IMPORTANCE_MIN),
        )
        manager.createNotificationChannel(
            NotificationChannel(FINISHED_CHANNEL, getString(R.string.notify_channel_finished), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(LIMITS_CHANNEL, getString(R.string.notify_channel_limits), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The service's own notification: quiet, and only there because Android asks for one. */
    private fun watchingNotification(): Notification =
        NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(getString(R.string.notify_watching_title))
            .setContentText(getString(R.string.notify_watching_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp())
            .build()

    /** One agent stopped working: say which, and where. */
    private fun notifyFinished(agent: io.github.werlang.whistl.data.MachineAgent) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val terminal = agent.agent.terminal
        val name = displayName(terminal.name, terminal.argv, terminal.address, terminal.labels)
        val where = agent.space?.name ?: agent.machineId
        val notification = NotificationCompat.Builder(this, FINISHED_CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(getString(R.string.notify_finished_title, name))
            .setContentText(getString(R.string.notify_finished_text, where))
            .setAutoCancel(true)
            .setContentIntent(openApp(agent.machineId, terminal.address))
            .build()
        NotificationManagerCompat.from(this).notify(FinishedAgents.key(agent).hashCode(), notification)
    }

    /** One provider window reached 90%: say which machine, provider and window, and how much is left. */
    private fun notifyLimit(alert: LimitAlert) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val machineName = container.machines.machines().value.firstOrNull { it.id == alert.machineId }?.name
            ?: alert.machineId
        val notification = NotificationCompat.Builder(this, LIMITS_CHANNEL)
            .setSmallIcon(R.drawable.ic_mark)
            .setContentTitle(
                getString(R.string.notify_limit_title, alert.providerTitle, alert.windowLabel, alert.usedPercentRounded),
            )
            .setContentText(getString(R.string.notify_limit_text, machineName, alert.remainingPercent))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        val computed = ("limit:" + LimitAlerts.key(alert.machineId, alert.providerId, alert.windowLabel, alert.resetsAtMs))
            .hashCode()
        // The service's own notification keeps WATCH_ID; a limit notification that lands on it takes the next id.
        val id = if (computed == WATCH_ID) WATCH_ID + 1 else computed
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun openApp(machineId: String? = null, address: String? = null): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (machineId != null && address != null) {
                putExtra(EXTRA_MACHINE, machineId)
                putExtra(EXTRA_ADDRESS, address)
            }
        }
        return PendingIntent.getActivity(
            this,
            (machineId.orEmpty() + address.orEmpty()).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** The channel the service's own notification uses. */
        const val WATCH_CHANNEL = "agent-watch"

        /** The channel a finished agent is announced on. */
        const val FINISHED_CHANNEL = "agent-finished"

        /** The channel a provider window that reached 90% is announced on. */
        const val LIMITS_CHANNEL = "limits-threshold"

        /** The service's notification id. */
        const val WATCH_ID = 1

        /** Extra naming the machine an agent runs on, when a notification is tapped. */
        const val EXTRA_MACHINE = "io.github.werlang.whistl.extra.MACHINE"

        /** Extra naming the terminal, when a notification is tapped. */
        const val EXTRA_ADDRESS = "io.github.werlang.whistl.extra.ADDRESS"

        /** Starts the watch, when the user wants notifications. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentWatchService::class.java))
        }

        /** Stops the watch. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AgentWatchService::class.java))
        }
    }
}
