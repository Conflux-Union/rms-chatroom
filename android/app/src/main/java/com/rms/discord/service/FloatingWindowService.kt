package com.rms.discord.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.rms.discord.R
import com.rms.discord.data.livekit.LiveKitManager
import com.rms.discord.data.livekit.ParticipantInfo
import com.rms.discord.data.local.SettingsPreferences
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Floating window service to display current speaking users during voice call.
 * Shows when app is in background and voice call is active.
 */
@AndroidEntryPoint
class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatingWindowService"

        fun canDrawOverlays(context: Context): Boolean {
            return Settings.canDrawOverlays(context)
        }

        fun show(context: Context) {
            if (!canDrawOverlays(context)) return
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        fun hide(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingWindowService::class.java))
        }

        private const val ACTION_SHOW = "com.rms.discord.ACTION_SHOW_FLOATING"
        private const val ACTION_HIDE = "com.rms.discord.ACTION_HIDE_FLOATING"
    }

    @Inject
    lateinit var liveKitManager: LiveKitManager

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isViewAttached = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        observeParticipants()
        observeMuteState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> showFloatingWindow()
            ACTION_HIDE -> hideFloatingWindow()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeFloatingView()
    }

    private fun observeParticipants() {
        serviceScope.launch {
            liveKitManager.participants.collectLatest { participants ->
                if (isViewAttached) {
                    updateFloatingContent(participants)
                }
            }
        }
    }

    private fun observeMuteState() {
        serviceScope.launch {
            liveKitManager.isMuted.collectLatest { isMuted ->
                if (isViewAttached) {
                    updateMuteButton(isMuted)
                }
            }
        }
    }

    private fun showFloatingWindow() {
        serviceScope.launch {
            val enabled = settingsPreferences.floatingWindowEnabled.first()
            if (!enabled || !canDrawOverlays(this@FloatingWindowService)) {
                return@launch
            }

            if (!isViewAttached) {
                createFloatingView()
            }
        }
    }

    private fun hideFloatingWindow() {
        removeFloatingView()
    }

    private fun createFloatingView() {
        if (floatingView != null || !canDrawOverlays(this)) return

        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_voice_window, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        // Setup drag functionality on title area only to allow button clicks
        val titleView = floatingView?.findViewById<TextView>(R.id.floating_title)
        val containerView = floatingView?.findViewById<LinearLayout>(R.id.speaking_users_container)
        
        val dragTouchListener = object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        }
        
        titleView?.setOnTouchListener(dragTouchListener)
        containerView?.setOnTouchListener(dragTouchListener)

        // Setup button click listeners
        setupButtonListeners()

        try {
            windowManager?.addView(floatingView, params)
            isViewAttached = true
            updateFloatingContent(liveKitManager.participants.value)
            updateMuteButton(liveKitManager.isMuted.value)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to add floating view", e)
        }
    }

    private fun setupButtonListeners() {
        val view = floatingView ?: return
        
        val muteBtn = view.findViewById<TextView>(R.id.btn_mute)
        val hangupBtn = view.findViewById<TextView>(R.id.btn_hangup)

        // Mute button click handler
        muteBtn?.setOnClickListener {
            val currentMuted = liveKitManager.isMuted.value
            liveKitManager.setMuted(!currentMuted)
        }

        // Hangup button click handler
        hangupBtn?.setOnClickListener {
            liveKitManager.disconnect()
            VoiceCallService.stop(this)
        }
    }

    private fun updateMuteButton(isMuted: Boolean) {
        val view = floatingView ?: return
        val muteBtn = view.findViewById<TextView>(R.id.btn_mute) ?: return
        muteBtn.text = if (isMuted) "取消静音" else "静音"
    }

    private fun removeFloatingView() {
        if (floatingView != null && isViewAttached) {
            try {
                windowManager?.removeView(floatingView)
            } catch (_: Exception) {}
            floatingView = null
            isViewAttached = false
        }
    }

    private fun updateFloatingContent(participants: List<ParticipantInfo>) {
        val view = floatingView ?: return
        val container = view.findViewById<LinearLayout>(R.id.speaking_users_container)
        val titleText = view.findViewById<TextView>(R.id.floating_title)
        
        // Find speaking participants (non-local, currently speaking)
        val speakingUsers = participants.filter { it.isSpeaking && !it.isLocal }
        
        container.removeAllViews()
        
        if (speakingUsers.isEmpty()) {
            titleText.text = "通话中"
            val noSpeakerView = TextView(this).apply {
                text = "暂无人发言"
                setTextColor(0xFFB5BAC1.toInt())
                textSize = 12f
                setPadding(0, 4, 0, 0)
            }
            container.addView(noSpeakerView)
        } else {
            titleText.text = "正在发言"
            speakingUsers.take(3).forEach { user ->
                val userView = TextView(this).apply {
                    text = "● ${user.name}"
                    setTextColor(0xFF43B581.toInt())
                    textSize = 13f
                    setPadding(0, 4, 0, 0)
                }
                container.addView(userView)
            }
            
            if (speakingUsers.size > 3) {
                val moreView = TextView(this).apply {
                    text = "还有 ${speakingUsers.size - 3} 人..."
                    setTextColor(0xFFB5BAC1.toInt())
                    textSize = 11f
                    setPadding(0, 4, 0, 0)
                }
                container.addView(moreView)
            }
        }
    }
}
