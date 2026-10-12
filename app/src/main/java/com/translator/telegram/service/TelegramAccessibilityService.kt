package com.translator.telegram.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    companion object {
        var instance: TelegramAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        overlayManager = OverlayWindowManager(this).apply {
            onBubbleClickListener = {
                scanAndTranslateCurrentScreen()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can optionally listen to new incoming messages and trigger auto-translation
        // For battery and performance efficiency, the primary trigger is user interaction (bubble)
    }

    override fun onInterrupt() {
        // Handle interruption
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        overlayManager?.hideBubble()
        overlayManager?.hideTranslationPopup()
        overlayManager = null
    }

    fun showFloatingBubble() {
        overlayManager?.showBubble()
    }

    fun hideFloatingBubble() {
        overlayManager?.hideBubble()
        overlayManager?.hideTranslationPopup()
    }

    /**
     * Scans currently visible screen content in Telegram (or current app),
     * extracts text nodes, sends them to translation API and shows overlay.
     */
    fun scanAndTranslateCurrentScreen() {
        val rootNode = rootInActiveWindow ?: return
        val extractedTexts = mutableListOf<String>()

        extractTextFromNode(rootNode, extractedTexts)
        rootNode.recycle()

        // Filter: Keep meaningful sentences, discard single characters, timestamps, etc.
        val foreignTexts = extractedTexts
            .map { it.trim() }
            .filter { text ->
                text.length >= 2 &&
                !isTimestampOrTime(text) &&
                !TranslationRepository.isAlreadyRussian(text)
            }
            .distinct()

        if (foreignTexts.isEmpty()) {
            overlayManager?.showTranslationPopup(emptyList(), isLoading = false)
            return
        }

        // Show loading popup
        overlayManager?.showTranslationPopup(emptyList(), isLoading = true)

        serviceScope.launch {
            val translationsMap = TranslationRepository.translateBatch(foreignTexts)
            val results = foreignTexts.map { text ->
                text to (translationsMap[text] ?: text)
            }
            overlayManager?.showTranslationPopup(results, isLoading = false)
        }
    }

    private fun extractTextFromNode(
        node: AccessibilityNodeInfo?,
        resultList: MutableList<String>
    ) {
        if (node == null) return

        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            resultList.add(text)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, resultList)
                child.recycle()
            }
        }
    }

    private fun isTimestampOrTime(text: String): Boolean {
        // Simple regex check for timestamps like "14:20", "2:30 PM", "09:15"
        val timeRegex = Regex("^\\d{1,2}:\\d{2}(\\s?(AM|PM|am|pm))?$")
        return timeRegex.matches(text) || text.all { it.isDigit() || it == ':' || it == '.' }
    }
}
