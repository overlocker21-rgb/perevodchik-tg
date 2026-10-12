package com.translator.telegram.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.translator.telegram.overlay.OverlayWindowManager
import com.translator.telegram.translation.TranslationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TelegramAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
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
        overlayManager = OverlayWindowManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

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

    fun scanAndTranslateCurrentScreen(onComplete: (List<Pair<String, String>>) -> Unit) {
        val rootNode = rootInActiveWindow
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root

        if (rootNode == null) {
            onComplete(listOf("Экран недоступен" to "Откройте чат Telegram на экране."))
            return
        }

        val extractedTexts = mutableListOf<String>()
        extractTextFromNode(rootNode, extractedTexts)

        val candidates = extractedTexts
            .map { it.trim() }
            .filter { text ->
                text.length >= 2 && !isTimestampOrTime(text)
            }
            .distinct()

        if (candidates.isEmpty()) {
            onComplete(listOf("Сообщения не найдены" to "На экране не обнаружено текстовых сообщений."))
            return
        }

        val foreignTexts = candidates.filter { text ->
            !TranslationRepository.isAlreadyRussian(text)
        }

        if (foreignTexts.isEmpty()) {
            onComplete(listOf("Все сообщения на русском" to "На текущем экране нет сообщений на иностранном языке."))
            return
        }

        serviceScope.launch {
            val translationsMap = TranslationRepository.translateBatch(foreignTexts)
            val results = foreignTexts.map { text ->
                text to (translationsMap[text] ?: text)
            }
            onComplete(results)
        }
    }

    private fun extractTextFromNode(
        node: AccessibilityNodeInfo?,
        resultList: MutableList<String>
    ) {
        if (node == null) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty()) {
            resultList.add(text)
        } else if (!desc.isNullOrEmpty()) {
            resultList.add(desc)
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
