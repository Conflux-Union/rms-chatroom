package cn.net.rms.chatroom

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.util.DebugLogger
import cn.net.rms.chatroom.data.auth.TokenAuthenticator
import cn.net.rms.chatroom.data.telemetry.TelemetryReporter
import cn.net.rms.chatroom.notification.NotificationHelper
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class RMSDiscordApp : Application(), ImageLoaderFactory {

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject
    lateinit var telemetryReporter: TelemetryReporter

    @Inject
    lateinit var tokenAuthenticator: TokenAuthenticator

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createNotificationChannels()
        telemetryReporter.installCrashHandler()
        telemetryReporter.uploadPendingCrashes()
    }

    /**
     * Optimized Coil image loader with memory and disk caching
     */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // Use 25% of app memory for image cache
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02) // Use 2% of available disk space
                    .build()
            }
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .networkCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .respectCacheHeaders(false) // Ignore server cache headers for better caching
            // Composables capture the access token when the request is built, so
            // it can expire before the image is fetched. The authenticator retries
            // the 401 with the rotated DataStore token instead of failing the load.
            .okHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .authenticator(tokenAuthenticator)
                    .build()
            )
            .apply {
                if (BuildConfig.DEBUG) {
                    logger(DebugLogger())
                }
            }
            .build()
    }
}
