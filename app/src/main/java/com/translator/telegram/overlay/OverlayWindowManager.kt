package com.translator.telegram.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.translator.telegram.R
import kotlin.math.hypot

class OverlayWindowManager private constructor(private val appContext: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: OverlayWindowManager? = null

        fun getInstance(context: Context): OverlayWindowManager {
            return instance ?: synchronized(this) {
                instance ?: OverlayWindowManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val themedContext = ContextThemeWrapper(appContext, R.style.Theme_TelegramTranslator)
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val inflater = LayoutInflater.from(themedContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var pillView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleAttached = false
    private var isPillAttached = false

    private var autoDismissRunnable: Runnable? = null

    var onBubbleClickListener: (() -> Unit)? = null
    var isBubbleActiveState: Boolean = false
        private set

    /**
     * Shows single floating draggable liquid glass bubble button.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (isBubbleAttached || !Settings.canDrawOverlays(appContext)) return

        try {
            bubbleView = inflater.inflate(R.layout.overlay_bubble, null)

            val displayMetrics = appContext.resources.displayMetrics
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
            updateBubbleVisuals()

            windowManager.addView(bubbleView, bubbleParams)
            isBubbleAttached = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Updates bubble look to indicate whether Auto-Translate mode is ON or OFF.
     */
    fun updateBubbleState(isActive: Boolean) {
        isBubbleActiveState = isActive
        mainHandler.post {
            updateBubbleVisuals()
        }
    }

    private fun updateBubbleVisuals() {
        val view = bubbleView ?: return
        val btnBubble = view.findViewById<ImageView>(R.id.btnBubble) ?: return
        val activeIndicator = view.findViewById<View>(R.id.bubbleActiveIndicator)

        if (isBubbleActiveState) {
            btnBubble.setBackgroundResource(R.drawable.bg_liquid_bubble_active)
            btnBubble.setColorFilter(Color.parseColor("#00E5FF"))
            activeIndicator?.visibility = View.VISIBLE
        } else {
            btnBubble.setBackgroundResource(R.drawable.bg_liquid_bubble)
            btnBubble.setColorFilter(Color.parseColor("#F0F8FF"))
            activeIndicator?.visibility = View.GONE
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
                    if (isBubbleAttached && bubbleView != null) {
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
                        if (isBubbleAttached && bubbleView != null) {
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
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isBubbleAttached = false
            bubbleView = null
        }
    }

    /**
     * Shows a compact liquid glass floating pill directly above the message.
     * Supports loading state and elevated Y coordinate to avoid overlapping the message.
     */
    fun showGlassPill(
        fullText: String,
        targetTop: Int = 0,
        targetBottom: Int = 0,
        isLoading: Boolean = false
    ) {
        if (!Settings.canDrawOverlays(appContext)) return

        val displayMetrics = appContext.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        // Ample vertical clearance so pill floats clearly ABOVE the message without covering it
        val estimatedPillHeight = (80 * density).toInt()
        val gapAboveMessage = (18 * density).toInt()
        val topBarLimit = (screenHeight * 0.08f).toInt()
        val bottomLimit = (screenHeight * 0.90f).toInt()

        val yPos = if (targetTop > 0) {
            val idealAbove = targetTop - estimatedPillHeight - gapAboveMessage
            if (idealAbove >= topBarLimit) {
                idealAbove
            } else if (targetBottom > 0 && (targetBottom + gapAboveMessage + estimatedPillHeight) <= bottomLimit) {
                targetBottom + gapAboveMessage // Below message if too close to top bar
            } else {
                topBarLimit + (6 * density).toInt()
            }
        } else {
            (screenHeight - (280 * density).toInt()).coerceAtLeast(topBarLimit)
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            x = 0
            y = yPos
        }

        // Smooth in-place update if pill is already on screen
        if (isPillAttached && pillView != null) {
            try {
                updatePillContent(pillView!!, fullText, isLoading)
                windowManager.updateViewLayout(pillView, pillParams)
                if (!isLoading) resetAutoDismiss()
                return
            } catch (e: Exception) {
                hideGlassPill()
            }
        }

        try {
            pillView = inflater.inflate(R.layout.overlay_glass_pill, null)
        } catch (e: Exception) {
            e.printStackTrace()
            if (!isLoading) Toast.makeText(appContext, fullText, Toast.LENGTH_LONG).show()
            return
        }

        updatePillContent(pillView!!, fullText, isLoading)

        val btnCopy = pillView?.findViewById<ImageView>(R.id.btnPillCopy)
        val btnClose = pillView?.findViewById<ImageView>(R.id.btnPillClose)

        btnCopy?.setOnClickListener {
            if (!isLoading) {
                val clip = ClipData.newPlainText("Translated message", fullText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(appContext, "Скопировано!", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose?.setOnClickListener {
            hideGlassPill()
        }

        pillView?.setOnClickListener {
            if (!isLoading) {
                val clip = ClipData.newPlainText("Translated message", fullText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(appContext, "Скопировано!", Toast.LENGTH_SHORT).show()
                hideGlassPill()
            }
        }

        try {
            windowManager.addView(pillView, pillParams)
            isPillAttached = true
            if (!isLoading) resetAutoDismiss()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updatePillContent(view: View, text: String, isLoading: Boolean) {
        val tvPillText = view.findViewById<TextView>(R.id.tvPillText)
        val ivPillIcon = view.findViewById<ImageView>(R.id.ivPillIcon)
        val pillProgressBar = view.findViewById<ProgressBar>(R.id.pillProgressBar)
        val btnCopy = view.findViewById<ImageView>(R.id.btnPillCopy)

        if (isLoading) {
            pillProgressBar?.visibility = View.VISIBLE
            ivPillIcon?.visibility = View.GONE
            tvPillText?.text = "Перевожу..."
            tvPillText?.setTextColor(Color.parseColor("#80FFFFFF"))
            btnCopy?.visibility = View.GONE
        } else {
            pillProgressBar?.visibility = View.GONE
            ivPillIcon?.visibility = View.VISIBLE
            tvPillText?.text = text
            tvPillText?.setTextColor(Color.parseColor("#FFFFFF"))
            btnCopy?.visibility = View.VISIBLE
        }
    }

    private fun resetAutoDismiss() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        autoDismissRunnable = Runnable { hideGlassPill() }
        mainHandler.postDelayed(autoDismissRunnable!!, 14000)
    }

    /**
     * Hides the compact liquid glass pill.
     */
    fun hideGlassPill() {
        autoDismissRunnable?.let { mainHandler.removeCallbacks(it) }
        if (!isPillAttached || pillView == null) return
        try {
            windowManager.removeView(pillView)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isPillAttached = false
            pillView = null
        }
    }

    fun isPillVisible(): Boolean = isPillAttached
    fun isBubbleVisible(): Boolean = isBubbleAttached
}
