package dev.walcott.enforcement

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.walcott.MainActivity
import dev.walcott.R
import dev.walcott.WalcottAdminReceiver
import dev.walcott.debug.DebugLog

/**
 * Makes this phone ring out loud on the parent's say-so (see `RemoteAction.RING_NOW`).
 *
 * The need is the oldest one a phone has: it is somewhere in the house and nobody can hear it.
 * [AudioGuard] keeps a CALL audible; this is for when nobody is calling, or when the phone is on
 * silent in a coat and a call would not be heard either.
 *
 * Played over the ALARM stream rather than the ring stream, and on purpose: alarms sound in
 * silent and vibrate mode, and are the one category Do Not Disturb lets through by default —
 * the three states a phone nobody can find is most likely to be in. The stream is turned up to
 * full for the duration and put back afterwards, because a phone found is a phone somebody is
 * now holding, and the alarm volume they had is theirs.
 *
 * Three ways it stops, all of which end in [stop]: the time the parent asked for runs out, the
 * phone is unlocked (somebody has it in their hand), or whoever picked it up taps "Found it" on
 * the notification. The notification is posted on its own channel, silent — the ring IS the
 * sound — and says whose phone this is ringing and why, so a finder is told rather than alarmed.
 *
 * Lives in the enforcement service's process, which is always up on a managed phone, so it
 * needs no service of its own; a process death mid-ring ends the ring, which is the safe way
 * round. Everything here runs on the main thread.
 *
 * The one thing it cannot promise: a process death inside the two minutes a ring can last at most
 * leaves the alarm stream where the ring put it, because the level it found is held in
 * memory and nowhere else. Persisting it would mean a disk write on every ring for a case that
 * needs the process to die inside a two-minute window — and the failure it would prevent is a
 * loud alarm clock, not a phone nobody can find.
 */
object Ringer {

    private const val TAG = "WalcottRing"
    private const val CHANNEL = "walcott_ring"
    private const val NOTIF_ID = 7_001

    /** The "Found it" tap; a receiver so it works from the lock screen and with the app closed. */
    const val ACTION_STOP = "dev.walcott.RING_STOP"

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var previousAlarmVolume = -1
    private var receiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeout = Runnable { stop(contextRef ?: return@Runnable, "time up") }
    private var contextRef: Context? = null

    /**
     * When the current ring is due to end, on this phone's wall clock; 0 = not ringing.
     *
     * A flow rather than a flag because two other things need to react the moment it changes:
     * this phone's own home screen, which is where somebody holding it looks for a way to make
     * it stop, and the snapshot, so the parent who started the noise is offered a way to end it
     * without having to find the phone first.
     */
    private val _ringingUntilMs = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val ringingUntilMs: kotlinx.coroutines.flow.StateFlow<Long> = _ringingUntilMs

    /** True while the phone is ringing on request. */
    val ringing: Boolean get() = _ringingUntilMs.value > 0L

    /** Seconds of ring still to run at [nowMs], for the snapshot the parent reads. */
    fun secondsLeft(nowMs: Long): Int {
        val until = _ringingUntilMs.value
        if (until <= nowMs) return 0
        // Rounded UP: a ring with 400ms left is still a ringing phone, and a 0 here is the
        // parent's button disappearing while the noise is still going.
        return ((until - nowMs + 999) / 1000).toInt()
    }

    /**
     * Starts ringing for [seconds]. Returns false when the platform gave it nothing to play with;
     * a phone that cannot ring must say so rather than ack a sound nobody will hear.
     */
    fun start(context: Context, seconds: Int): Boolean {
        val app = context.applicationContext
        if (ringing) stop(app, "rung again")
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        val tone = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return false

        // A Device Owner can undo a muted master volume outright; the stream below cannot.
        runCatching {
            val dpm = app.getSystemService(DevicePolicyManager::class.java)
            if (dpm != null && dpm.isDeviceOwnerApp(app.packageName)) {
                dpm.setMasterVolumeMuted(WalcottAdminReceiver.componentName(app), false)
            }
        }
        previousAlarmVolume = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrDefault(-1)
        runCatching {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }.onFailure { DebugLog.w(TAG, "could not raise the alarm volume", it) }

        val created = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(app, tone)
                isLooping = true
                // The CPU stays up for as long as the sound does: a phone ringing from a drawer
                // with its screen off is the whole use case.
                setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
                // Prepared asynchronously: a synchronous prepare decodes on the caller's thread,
                // which here is the main one, and a tone that resolves to a slow provider held
                // the UI for its whole load.
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    DebugLog.e(TAG, "the ring tone failed to play (what=$what extra=$extra)")
                    true
                }
                prepareAsync()
            }
        }.onFailure { DebugLog.e(TAG, "could not play the ring tone", it) }.getOrNull()
        if (created == null) {
            restoreVolume(audio)
            return false
        }
        player = created
        contextRef = app
        _ringingUntilMs.value = System.currentTimeMillis() + seconds * 1_000L

        // Somebody unlocking the phone has found it. Not SCREEN_ON: a finder pressing the power
        // button to see whose phone this is has not found anything yet, and the line on the
        // screen is what tells them.
        val stopReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> stop(ctx, "unlocked")
                    ACTION_STOP -> stop(ctx, "found it")
                }
            }
        }
        ContextCompat.registerReceiver(
            app, stopReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(ACTION_STOP)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = stopReceiver
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, seconds * 1_000L)
        postNotification(app)
        DebugLog.w(TAG, "ringing on request for $seconds s")
        return true
    }

    /** Ends the ring, if one is running, and puts everything back. Safe to call twice. */
    fun stop(context: Context, reason: String) {
        val app = context.applicationContext
        if (!ringing) return
        _ringingUntilMs.value = 0L
        handler.removeCallbacks(timeout)
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        app.getSystemService(AudioManager::class.java)?.let { restoreVolume(it) }
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        contextRef = null
        DebugLog.i(TAG, "ring stopped ($reason)")
    }

    private fun restoreVolume(audio: AudioManager) {
        if (previousAlarmVolume < 0) return
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0) }
        previousAlarmVolume = -1
    }

    private fun postNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.ring_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                    .apply {
                        description = context.getString(R.string.ring_channel_desc)
                        // The ring is the sound. A notification tone over it is noise.
                        setSound(null, null)
                        enableVibration(false)
                    },
            )
        }
        val stop = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.ring_notif_title))
            .setContentText(context.getString(R.string.ring_notif_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.ring_notif_text)))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.ring_found_it), stop)
            .build()
        runCatching { nm.notify(NOTIF_ID, notification) }
    }
}
