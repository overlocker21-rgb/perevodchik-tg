package com.translator.telegram.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.translator.telegram.overlay.OverlayWindowManager
import com.translator.telegram.translation.TranslationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class TelegramAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val debounceHandler = Handler(Looper.getMainLooper())

    var isAutoTranslateActive: Boolean = false
        private set

    private var currentMessageText: String? = null
    private var currentMessageTop: Int = 0

    private val autoScanRunnable = Runnable {
        if (isAutoTranslateActive) {
            scanAndTranslateLowestVisibleMessage()
        }
    }

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

        private val IGNORED_SYSTEM_WORDS = hashSetOf(
            "message", "сообщение", "comment", "комментарий", "reply", "ответить",
            "forward", "переслать", "broadcast", "voice message", "голосовое сообщение",
            "video message", "видеосообщение", "send", "отправить", "attach", "прикрепить",
            "mute", "unmute", "join", "discuss", "pinned message", "закрепленное сообщение",
            "online", "в сети", "last seen", "был(а) недавно", "typing...", "печатает...",
            "connecting...", "соединение...", "yesterday", "вчера", "today", "сегодня",
            "stickers", "стикеры", "emoji", "эмодзи", "gif", "edit", "изменить", "cancel", "отмена"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /**
     * Toggles auto-translation mode on or off.
     */
    fun toggleAutoTranslate() {
        isAutoTranslateActive = !isAutoTranslateActive
        val overlay = OverlayWindowManager.getInstance(this)
        overlay.updateBubbleState(isAutoTranslateActive)

        if (isAutoTranslateActive) {
            Toast.makeText(this, "Режим автоперевода: ВКЛ", Toast.LENGTH_SHORT).show()
            currentMessageText = null
            currentMessageTop = 0
            // Immediately scan screen
            debounceHandler.removeCallbacks(autoScanRunnable)
            debounceHandler.postDelayed(autoScanRunnable, 50)
        } else {
            Toast.makeText(this, "Режим автоперевода: ВЫКЛ", Toast.LENGTH_SHORT).show()
            debounceHandler.removeCallbacks(autoScanRunnable)
            currentMessageText = null
            currentMessageTop = 0
            overlay.hideGlassPill()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoTranslateActive) return
        val eventPackage = event?.packageName?.toString() ?: return

        // Skip events from our own overlay
        if (eventPackage == packageName) return

        val isChatApp = eventPackage.contains("telegram", ignoreCase = true) ||
                        eventPackage.contains("challegram", ignoreCase = true) ||
                        eventPackage.contains("nekox", ignoreCase = true) ||
                        eventPackage.contains("messenger", ignoreCase = true)

        if (!isChatApp) return

        // Fast response: 120ms debounce so it starts translating as soon as finger lifts
        debounceHandler.removeCallbacks(autoScanRunnable)
        debounceHandler.postDelayed(autoScanRunnable, 120)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        debounceHandler.removeCallbacks(autoScanRunnable)
        OverlayWindowManager.getInstance(this).apply {
            updateBubbleState(false)
            hideGlassPill()
        }
    }

    /**
     * Finds the lowest incoming (not user's) foreign message visible on screen,
     * immediately shows the loading pill above it, translates, and displays full text.
     */
    fun scanAndTranslateLowestVisibleMessage() {
        val rootNode = rootInActiveWindow ?: return

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density

        val topLimit = (screenHeight * 0.08f).toInt()
        val bottomLimit = screenHeight - (70 * density).toInt()

        val items = mutableListOf<ScannedItem>()
        extractIncomingTextFromNode(rootNode, items, screenWidth, topLimit, bottomLimit)

        val validItems = items
            .map { ScannedItem(cleanMessageText(it.text), it.top, it.bottom) }
            .filter { isValidMessageText(it.text) }

        val overlay = OverlayWindowManager.getInstance(this)

        if (validItems.isEmpty()) return

        // Filter out Russian messages
        val foreignItems = validItems.filter { !TranslationRepository.isAlreadyRussian(it.text) }

        if (foreignItems.isEmpty()) {
            // All visible incoming messages are in Russian — hide pill
            overlay.hideGlassPill()
            currentMessageText = null
            currentMessageTop = 0
            return
        }

        // Pick the lowest (bottom-most) incoming foreign message visible on screen
        val targetMessage = foreignItems.maxByOrNull { it.bottom } ?: return

        val isPillVisible = overlay.isPillVisible()
        val hasTextChanged = targetMessage.text != currentMessageText
        val hasMoved = abs(targetMessage.top - currentMessageTop) > 35

        // If pill is already showing this text and has not moved, skip
        if (!hasTextChanged && isPillVisible && !hasMoved) {
            return
        }

        currentMessageText = targetMessage.text
        currentMessageTop = targetMessage.top

        // 1. Immediately show loading pill above message so user sees instant feedback
        overlay.showGlassPill(
            fullText = "",
            targetTop = targetMessage.top,
            targetBottom = targetMessage.bottom,
            isLoading = true
        )

        // 2. Fetch translation in background
        serviceScope.launch {
            val translated = TranslationRepository.translate(targetMessage.text)
            val prefix = TranslationRepository.getPrefix(this@TelegramAccessibilityService)
            val fullResult = "${prefix}${translated}"

            mainHandler.post {
                if (isAutoTranslateActive && currentMessageText == targetMessage.text) {
                    overlay.showGlassPill(
                        fullText = fullResult,
                        targetTop = targetMessage.top,
                        targetBottom = targetMessage.bottom,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun extractIncomingTextFromNode(
        node: AccessibilityNodeInfo?,
        resultList: MutableList<ScannedItem>,
        screenWidth: Int,
        topLimit: Int,
        bottomLimit: Int
    ) {
        if (node == null) return

        // Skip editable input boxes
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            return
        }

        // Skip our own overlay window
        if (node.packageName?.toString() == packageName) {
            return
        }

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        val isOutgoing = desc?.contains("Отправлено", ignoreCase = true) == true ||
                         desc?.contains("Sent", ignoreCase = true) == true

        // Prefer pure text, fall back to content description
        val raw = when {
            !text.isNullOrEmpty() -> text
            !desc.isNullOrEmpty() && !isOutgoing -> desc
            else -> null
        }

        if (!raw.isNullOrEmpty() && !isOutgoing) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // CRITICAL: Incoming messages are strictly aligned to the LEFT (rect.left < 35% of screen)
            // Outgoing messages from user are on the right (rect.left > 35% of screen)
            val isIncomingLeftAligned = rect.left < (screenWidth * 0.35f)

            if (isIncomingLeftAligned && rect.height() > 0 && rect.bottom > 0 &&
                rect.top >= topLimit && rect.bottom <= bottomLimit) {
                resultList.add(ScannedItem(raw, rect.top, rect.bottom))
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            extractIncomingTextFromNode(child, resultList, screenWidth, topLimit, bottomLimit)
            try {
                child.recycle()
            } catch (e: Exception) {}
        }
    }

    private fun cleanMessageText(raw: String): String {
        return raw
            .replace(Regex(",?\\s*(Получено|Отправлено|изменено|edited)\\s+(в|at)?\\s*\\d{1,2}:\\d{2}", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b\\d{1,2}:\\d{2}\\b"), "")
            .replace("✓", "")
            .replace("✔", "")
            .trim()
    }

    private fun isValidMessageText(text: String): Boolean {
        if (text.length < 2) return false
        val lower = text.lowercase()
        if (IGNORED_SYSTEM_WORDS.contains(lower)) return false
        if (isTimestampOrTime(text)) return false
        // Exclude pure numbers / counters / status icons
        if (text.all { it.isDigit() || it == '.' || it == ':' || it == 'k' || it == 'K' }) {
            return false
        }
        return true
    }

    private fun isTimestampOrTime(text: String): Boolean {
        val clean = text.replace("✓", "").replace("✔", "").trim()
        val timeRegex = Regex("^\\d{1,2}:\\d{2}(\\s?(AM|PM|am|pm))?$")
        if (timeRegex.matches(clean)) return true
        if (clean.all { it.isDigit() || it == ':' || it == '.' || it == ' ' || it == '/' || it == '-' }) return true
        return false
    }
}
