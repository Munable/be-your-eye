package app.beyoureyes.monitor

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationChannelsInstrumentedTest {
    @Test
    fun optedInLocalAndSameAccountRemoteEventsBothAlert() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(NotificationChannels.LOCAL_EVENT_CHANNEL_ID)
        manager.deleteNotificationChannel(NotificationChannels.REMOTE_EVENT_CHANNEL_ID)

        NotificationChannels.ensureCreated(context)

        val local = checkNotNull(
            manager.getNotificationChannel(NotificationChannels.LOCAL_EVENT_CHANNEL_ID),
        )
        val remote = checkNotNull(
            manager.getNotificationChannel(NotificationChannels.REMOTE_EVENT_CHANNEL_ID),
        )
        assertEquals(NotificationManager.IMPORTANCE_HIGH, local.importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, remote.importance)
        assertEquals(
            NotificationChannels.LOCAL_EVENT_CHANNEL_ID,
            NotificationChannels.localConfirmedEvent(context, "local-1", "本机事件").channelId,
        )
        assertEquals(
            NotificationChannels.REMOTE_EVENT_CHANNEL_ID,
            NotificationChannels.remoteConfirmedEvent(context, "remote-1", "其他手机事件").channelId,
        )
    }

    @Test
    fun eventNotificationTargetsHistoryAndPreservesTheEventIdentity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = NotificationChannels.eventHistoryIntent(context, "event-1")

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("event-1", intent.getStringExtra(NotificationChannels.EXTRA_EVENT_ID))
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags,
        )
    }
}
