package com.translator.telegram.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.translator.telegram.R
import kotlin.math.abs

class OverlayWindowManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var bubbleView: View? = null
    private var popupView: View? = null

    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleAttached = false
    private var isPopupAttached = false

    var onBubbleClickListener: (() -> Unit)? = null

    /**
     * Shows floating draggable bubble.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showBubble() {
        if (isBubbleAttached || !Settings.canDrawOverlays(context)) return

        val inflater = LayoutInflater.from(context)
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
            x = screenWidth - 180
            y = 350
        }

        setupBubbleTouchListener(bubbleView!!, bubbleParams!!, screenWidth)

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
        var isClick = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (abs(dx) > 12 || abs(dy) > 12) {
                        isClick = false
                    }

                    params.x = initialX + dx
                    params.y = initialY + dy
                    if (isBubbleAttached) {
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        onBubbleClickListener?.invoke()
                    } else {
                        // Snap bubble to nearest screen edge (left or right)
                        params.x = if (params.x < screenWidth / 2) 20 else screenWidth - view.width - 20
                        if (isBubbleAttached) {
                            windowManager.updateViewLayout(view, params)
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
     * Shows floating translation popup card with translated results.
     */
    fun showTranslationPopup(
        translations: List<Pair<String, String>>,
        isLoading: Boolean = false
    ) {
        if (!Settings.canDrawOverlays(context)) return

        if (!isPopupAttached || popupView == null) {
            val inflater = LayoutInflater.from(context)
            popupView = inflater.inflate(R.layout.overlay_translation_popup, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val popupWidth = (displayMetrics.widthPixels * 0.90).toInt()

            val popupParams = WindowManager.LayoutParams(
                popupWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
                y = 160
            }

            popupView?.findViewById<ImageView>(R.id.btnClosePopup)?.setOnClickListener {
                hideTranslationPopup()
            }

            try {
                windowManager.addView(popupView, popupParams)
                isPopupAttached = true
            } catch (e: Exception) {
                e.printStackTrace()
                return
            }
        }

        // Update popup contents
        val progressBar = popupView?.findViewById<ProgressBar>(R.id.progressBar)
        val container = popupView?.findViewById<LinearLayout>(R.id.translationsContainer)
        val title = popupView?.findViewById<TextView>(R.id.tvPopupTitle)

        progressBar?.visibility = if (isLoading) View.VISIBLE else View.GONE
        container?.removeAllViews()

        if (translations.isEmpty()) {
            title?.text = if (isLoading) "Сканирование экрана..." else "Сообщений для перевода не найдено"
        } else {
            title?.text = "Переведено (${translations.size} сообщ.)"

            val inflater = LayoutInflater.from(context)
            for ((orig, trans) in translations) {
                val itemView = inflater.inflate(R.layout.item_translation, container, false)
                val tvOriginal = itemView.findViewById<TextView>(R.id.tvOriginal)
                val tvTranslated = itemView.findViewById<TextView>(R.id.tvTranslated)
                val btnCopy = itemView.findViewById<ImageView>(R.id.btnCopy)

                tvOriginal.text = orig
                tvTranslated.text = trans

                btnCopy.setOnClickListener {
                    val clip = ClipData.newPlainText("Translated message", trans)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Скопировано в буфер!", Toast.LENGTH_SHORT).show()
                }

                container?.addView(itemView)
            }
        }
    }

    /**
     * Hides floating translation popup card.
     */
    fun hideTranslationPopup() {
        if (!isPopupAttached || popupView == null) return
        try {
            windowManager.removeView(popupView)
            isPopupAttached = false
            popupView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isBubbleVisible(): Boolean = isBubbleAttached
}
