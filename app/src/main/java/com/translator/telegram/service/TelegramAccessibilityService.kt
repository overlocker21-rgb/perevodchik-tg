package com.translator.telegram.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.translator.telegram.overlay.OverlayWindowManager
import com.translator.telegram.translation.TranslationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TelegramAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayManager: OverlayWindowManager? = null

    data class ScannedItem(
        val text: String,
        val top: Int,
        val bottom: Int
    )

    companion object {
        var instance: TelegramAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = OverlayWindowManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlayManager?.hideBubble()
        overlayManager?.hideGlassPill()
        overlayManager = null
    }

    fun showFloatingBubble() {
        overlayManager?.showBubble()
    }

    fun hideFloatingBubble() {
        overlayManager?.hideBubble()
        overlayManager?.hideGlassPill()
    }

    /**
     * Finds the latest foreign message on screen and shows a compact
     * Liquid Glass pill right above it with the custom prefix.
     */
    fun scanAndTranslateCurrentScreen(onComplete: ((List<Pair<String, String>>) -> Unit)? = null) {
        if (overlayManager == null) {
            overlayManager = OverlayWindowManager(this)
        }

        val items = mutableListOf<ScannedItem>()

        // 1. Scan root in active window
        rootInActiveWindow?.let { extractTextFromNode(it, items) }

        // 2. Also scan accessible windows
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    window.root?.let { extractTextFromNode(it, items) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val validItems = items
            .map { ScannedItem(it.text.trim(), it.top, it.bottom) }
            .filter { it.text.length >= 2 && !isTimestampOrTime(it.text) }

        if (validItems.isEmpty()) {
            mainHandler.post {
                Toast.makeText(this, "Сообщения на экране не найдены", Toast.LENGTH_SHORT).show()
            }
            onComplete?.invoke(emptyList())
            return
        }

        val foreignItems = validItems.filter { !TranslationRepository.isAlreadyRussian(it.text) }

        if (foreignItems.isEmpty()) {
            mainHandler.post {
                overlayManager?.showGlassPill("Все сообщения на русском")
            }
            onComplete?.invoke(listOf("Все сообщения на русском" to "На экране нет иностранного текста"))
            return
        }

        // Pick the latest (lowest on screen) foreign message
        val targetMessage = foreignItems.maxByOrNull { it.bottom } ?: foreignItems.last()

        serviceScope.launch {
            val translated = TranslationRepository.translate(targetMessage.text)
            val prefix = TranslationRepository.getPrefix(this@TelegramAccessibilityService)
            val fullResult = "${prefix}${translated}"

            mainHandler.post {
                overlayManager?.showGlassPill(fullResult, targetMessage.top)
            }
            onComplete?.invoke(listOf(targetMessage.text to fullResult))
        }
    }

    private fun extractTextFromNode(
        node: AccessibilityNodeInfo?,
        resultList: MutableList<ScannedItem>
    ) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        val raw = if (!text.isNullOrEmpty()) text else desc
        if (!raw.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.height() > 0 && rect.bottom > 0) {
                resultList.add(ScannedItem(raw, rect.top, rect.bottom))
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, resultList)
                try {
                    child.recycle()
                } catch (e: Exception) {}
            }
        }
    }

    private fun isTimestampOrTime(text: String): Boolean {
        val timeRegex = Regex("^\\d{1,2}:\\d{2}(\\s?(AM|PM|am|pm))?$")
        return timeRegex.matches(text) || text.all { it.isDigit() || it == ':' || it == '.' }
    }
}
