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
     * Called whenever the floating glass bubble button is tapped.
     */
    fun toggleAutoTranslate() {
        isAutoTranslateActive = !isAutoTranslateActive
        val overlay = OverlayWindowManager.getInstance(this)
        overlay.updateBubbleState(isAutoTranslateActive)

        if (isAutoTranslateActive) {
            Toast.makeText(this, "Режим автоперевода: ВКЛ", Toast.LENGTH_SHORT).show()
            currentMessageText = null
            currentMessageTop = 0
            // Immediately scan the current screen
            debounceHandler.removeCallbacks(autoScanRunnable)
            debounceHandler.postDelayed(autoScanRunnable, 100)
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

        // Skip events from our own application overlay
        if (eventPackage == packageName) return

        // Only listen for events from Telegram or compatible messengers
        val isChatApp = eventPackage.contains("telegram", ignoreCase = true) ||
                        eventPackage.contains("challegram", ignoreCase = true) ||
                        eventPackage.contains("nekox", ignoreCase = true) ||
                        eventPackage.contains("messenger", ignoreCase = true)

        if (!isChatApp) return

        // Debounce events (when user scrolls or stops scrolling, settle in 350ms)
        debounceHandler.removeCallbacks(autoScanRunnable)
        debounceHandler.postDelayed(autoScanRunnable, 350)
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
     * Scans active screen, dynamically detects input box boundary,
     * finds the lowest (bottom-most) foreign message currently visible on screen,
     * and shows the translation pill directly above it.
     */
    fun scanAndTranslateLowestVisibleMessage() {
        val rootNode = rootInActiveWindow ?: return

        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        val topLimit = (screenHeight * 0.08f).toInt()
        val defaultBottomLimit = (screenHeight * 0.90f).toInt()

        // Dynamically find where the input bar begins so we never scan input text/hints
        val inputBarTop = findInputBarTop(rootNode, defaultBottomLimit)
        val bottomLimit = (inputBarTop - (4 * density).toInt()).coerceAtMost(defaultBottomLimit)

        val items = mutableListOf<ScannedItem>()
        extractTextFromNode(rootNode, items, topLimit, bottomLimit)

        val validItems = items
            .map { ScannedItem(it.text.trim(), it.top, it.bottom) }
            .filter { isValidMessageText(it.text) }

        if (validItems.isEmpty()) return

        // Filter out Russian messages
        val foreignItems = validItems.filter { !TranslationRepository.isAlreadyRussian(it.text) }

        val overlay = OverlayWindowManager.getInstance(this)

        if (foreignItems.isEmpty()) {
            // All currently visible messages are Russian — hide the pill
            overlay.hideGlassPill()
            currentMessageText = null
            currentMessageTop = 0
            return
        }

        // Pick the lowest (bottom-most) foreign message visible on screen right now
        val targetMessage = foreignItems.maxByOrNull { it.bottom } ?: return

        val isPillVisible = overlay.isPillVisible()
        val hasTextChanged = targetMessage.text != currentMessageText
        val hasMoved = abs(targetMessage.top - currentMessageTop) > 35

        // If pill is visible, text hasn't changed and position hasn't moved, do nothing
        if (!hasTextChanged && isPillVisible && !hasMoved) {
            return
        }

        currentMessageText = targetMessage.text
        currentMessageTop = targetMessage.top

        serviceScope.launch {
            val translated = TranslationRepository.translate(targetMessage.text)
            val prefix = TranslationRepository.getPrefix(this@TelegramAccessibilityService)
            val fullResult = "${prefix}${translated}"

            mainHandler.post {
                if (isAutoTranslateActive) {
                    overlay.showGlassPill(fullResult, targetMessage.top, targetMessage.bottom)
                }
            }
        }
    }

    /**
     * Traverses the node tree to locate the top edge of any input field / EditText.
     */
    private fun findInputBarTop(root: AccessibilityNodeInfo, defaultBottom: Int): Int {
        var minInputTop = defaultBottom
        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.top in 1 until minInputTop) {
                    minInputTop = rect.top
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                scan(child)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
        scan(root)
        return minInputTop
    }

    private fun extractTextFromNode(
        node: AccessibilityNodeInfo?,
        resultList: MutableList<ScannedItem>,
        topLimit: Int,
        bottomLimit: Int
    ) {
        if (node == null) return

        // Ignore editable input boxes
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            return
        }

        // Ignore our own overlay window
        if (node.packageName?.toString() == packageName) {
            return
        }

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val raw = if (!text.isNullOrEmpty()) text else desc

        if (!raw.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // Message must be strictly inside the chat area (between top bar and bottom input)
            if (rect.height() > 0 && rect.bottom > 0 && rect.top >= topLimit && rect.bottom <= bottomLimit) {
                resultList.add(ScannedItem(raw, rect.top, rect.bottom))
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractTextFromNode(child, resultList, topLimit, bottomLimit)
                try {
                    child.recycle()
                } catch (e: Exception) {}
            }
        }
    }

    private fun isValidMessageText(text: String): Boolean {
        if (text.length < 2) return false
        val lower = text.lowercase()
        if (IGNORED_SYSTEM_WORDS.contains(lower)) return false
        if (isTimestampOrTime(text)) return false
        // Exclude pure numbers / counters / status icons
        val clean = text.replace("✓", "").replace("✔", "").trim()
        if (clean.isEmpty() || clean.all { it.isDigit() || it == '.' || it == ':' || it == 'k' || it == 'K' }) {
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
