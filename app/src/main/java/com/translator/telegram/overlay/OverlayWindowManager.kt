package com.translator.telegram.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.translator.telegram.R
import kotlin.math.hypot

class OverlayWindowManager(private val context: Context) {

    private val themedContext = ContextThemeWrapper(context, R.style.Theme_TelegramTranslator)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val inflater = LayoutInflater.from(themedContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var pillView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleAttached = false
    private var isPillAttached = false

    private var autoDismissRunnable: Runnable? = null

    var onBubbleClickListener: (() -> Unit)? = null

    /**
     * Shows floating draggable liquid glass bubble button.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (isBubbleAttached || !Settings.canDrawOverlays(context)) return

        bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenWidth = displayMetrics.widthPixels

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - 170).coerceAtLeast(20)
            y = 400
        }

        setupBubbleTouchListener(bubbleView!!, bubbleParams!!, if (screenWidth > 0) screenWidth else 1080)

        try {
            windowManager.addView(bubbleView, bubbleParams)
            isBubbleAttached = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupBubbleTouchListener(
        view: View,
        params: WindowManager.LayoutParams,
        screenWidth: Int
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    params.x = initialX + dx
                    params.y = initialY + dy
                    if (isBubbleAttached) {
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val duration = System.currentTimeMillis() - touchStartTime
                    val distance = hypot(
                        (event.rawX - initialTouchX).toDouble(),
                        (event.rawY - initialTouchY).toDouble()
                    )

                    // Touch within 500ms and 45px distance is considered a click
                    if (duration < 500 && distance < 45.0) {
                        onBubbleClickListener?.invoke()
                    } else {
                        // Snap bubble to nearest screen edge (left or right)
                        params.x = if (params.x < screenWidth / 2) 20 else (screenWidth - 170).coerceAtLeast(20)
                        if (isBubbleAttached) {
                            try {
                                windowManager.updateViewLayout(view, params)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Removes floating bubble from screen.
     */
    fun hideBubble() {
        if (!isBubbleAttached || bubbleView == null) return
        try {
            windowManager.removeView(bubbleView)
            isBubbleAttached = false
            bubbleView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Shows a compact liquid glass floating pill directly above the message.
     * iOS 26 / Liquid Glass style.
     */
    fun showGlassPill(
        fullText: String,
        targetY: Int = 0
    ) {
        if (!Settings.canDrawOverlays(context)) return

        // Hide previous pill if visible
        hideGlassPill()

        try {
            pillView = inflater.inflate(R.layout.overlay_glass_pill, null)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, fullText, Toast.LENGTH_LONG).show()
            return
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(displayMetrics)
        val screenHeight = displayMetrics.heightPixels

        // Position pill directly above the message bounds or neatly centered
        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            y = when {
                targetY > 200 -> (targetY - 140).coerceAtLeast(150)
                targetY in 1..200 -> (targetY + 90)
                else -> (screenHeight - 340).coerceAtLeast(200) // Near bottom above input bar
            }
        }

        val tvPillText = pillView?.findViewById<TextView>(R.id.tvPillText)
        val btnCopy = pillView?.findViewById<ImageView>(R.id.btnPillCopy)
        val btnClose = pillView?.findViewById<ImageView>(R.id.btnPillClose)

        tvPillText?.text = fullText

        // Tap on copy icon
        btnCopy?.setOnClickListener {
            val clip = ClipData.newPlainText("Translated message", fullText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Скопировано!", Toast.LENGTH_SHORT).show()
        }

        // Tap on close icon
        btnClose?.setOnClickListener {
            hideGlassPill()
        }

        // Tap anywhere on the pill to copy & dismiss
        pillView?.setOnClickListener {
            val clip = ClipData.newPlainText("Translated message", fullText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Скопировано!", Toast.LENGTH_SHORT).show()
            hideGlassPill()
        }

        try {
            windowManager.addView(pillView, pillParams)
            isPillAttached = true

            // Auto-dismiss pill after 9 seconds so it doesn't clutter the screen
            autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
            autoDismissRunnable = Runnable { hideGlassPill() }
            mainHandler.postDelayed(autoDismissRunnable!!, 9000)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Hides the compact liquid glass pill.
     */
    fun hideGlassPill() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!isPillAttached || pillView == null) return
        try {
            windowManager.removeView(pillView)
            isPillAttached = false
            pillView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Backwards compatibility alias
    fun showTranslationPopup(translations: List<Pair<String, String>>, isLoading: Boolean = false) {
        if (isLoading) {
            Toast.makeText(context, "Сканирую экран Telegram...", Toast.LENGTH_SHORT).show()
        } else if (translations.isNotEmpty()) {
            val first = translations.first().second
            showGlassPill(first)
        }
    }

    fun hideTranslationPopup() {
        hideGlassPill()
    }

    fun isBubbleVisible(): Boolean = isBubbleAttached
}
