package az.temiz

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CacheAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CacheAccessibilityService? = null

        @Volatile
        var isRunning: Boolean = false

        @Volatile
        var totalPackages: Int = 0

        @Volatile
        var currentIndex: Int = 0

        @Volatile
        var currentAppName: String = ""

        @Volatile
        var cleanedCount: Int = 0

        @Volatile
        var clearedBytes: Long = 0L

        private var queue: List<CacheTarget> = emptyList()
        // 0 = App Info screen, 1 = Storage sub-screen (AOSP/Samsung), 2 = MIUI/HyperOS bottom sheet, 3 = Confirm dialog
        private var stepState: Int = 0
        private var returnToAppAfter: Boolean = false
        private val handler = Handler(Looper.getMainLooper())
        private var timeoutRunnable: Runnable? = null

        fun isEnabledInSettings(context: Context): Boolean {
            if (instance != null) return true
            val expected = ComponentName(context, CacheAccessibilityService::class.java)
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                val comp = ComponentName.unflattenFromString(splitter.next())
                if (comp != null && comp == expected) return true
            }
            return false
        }

        fun startBatch(targets: List<CacheTarget>, returnToApp: Boolean = true): Boolean {
            val svc = instance ?: return false
            if (isRunning) return false
            if (targets.isEmpty()) return false

            queue = targets
            totalPackages = targets.size
            currentIndex = 0
            cleanedCount = 0
            clearedBytes = 0L
            returnToAppAfter = returnToApp
            isRunning = true

            svc.processNext()
            return true
        }

        fun stopBatch() {
            val wasReturn = returnToAppAfter
            isRunning = false
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            queue = emptyList()
            currentAppName = ""
            if (wasReturn) {
                instance?.bringAppToFront()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun onInterrupt() {
        stopBatch()
    }

    private fun bringAppToFront() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun processNext() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }

        if (!isRunning || currentIndex >= queue.size) {
            val doneCount = cleanedCount
            val doneBytes = clearedBytes
            isRunning = false
            currentAppName = ""
            if (doneCount > 0) {
                Cleaner.recordExternalCacheClean(this, doneCount, doneBytes)
            }
            if (returnToAppAfter) {
                handler.postDelayed({ bringAppToFront() }, 350L)
            }
            return
        }

        val target = queue[currentIndex]
        currentAppName = target.name
        stepState = 0

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${target.pkg}")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            startActivity(intent)
        } catch (_: Exception) {
            currentIndex++
            handler.postDelayed({ processNext() }, 150L)
            return
        }

        scheduleStepTimeout(2400L)
    }

    private fun scheduleStepTimeout(delayMs: Long) {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (!isRunning) return@Runnable
            val root = rootInActiveWindow
            if (root != null && tryHandleWindow(root)) {
                return@Runnable
            }
            currentIndex++
            processNext()
        }
        timeoutRunnable = r
        handler.postDelayed(r, delayMs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        val root = rootInActiveWindow ?: return
        tryHandleWindow(root)
    }

    private fun tryHandleWindow(root: AccessibilityNodeInfo): Boolean {
        if (!isRunning || currentIndex >= queue.size) return false

        val storageKeywords = listOf(
            "yaddaş", "storage", "хранилище", "память", "depolama"
        )

        // Strictly cache-only labels across AZ, EN, RU, TR
        val clearCacheKeywords = listOf(
            "keşi təmizlə", "keşi sil", "keş yaddaşı təmizlə",
            "clear cache",
            "очистить кэш", "очистка кэша",
            "önbelleği temizle"
        )

        // Forbidden words when clicking the final clear button (protects user app data!)
        val strictDataForbidden = listOf(
            "məlumat", "data", "данные", "verileri", "all", "hamısı", "bütün", "все", "tüm"
        )

        // MIUI / HyperOS bottom toolbar button that opens the popup containing "Keşi təmizlə / Clear cache"
        val miuiMenuKeywords = listOf(
            "məlumatları təmizlə", "clear data", "очистить", "verileri temizle"
        )
        val miuiMenuForbidden = listOf(
            "hamısını", "bütün", "all", "все", "tüm"
        )

        val confirmKeywords = listOf(
            "ok", "tamam", "təmizlə", "sil", "очистить", "да"
        )

        when (stepState) {
            0 -> {
                // 1) Direct "Clear cache" button visible on App Info screen
                val directCacheNode = findMatchingNode(root, clearCacheKeywords, strictDataForbidden)
                if (directCacheNode != null) {
                    if (directCacheNode.isEnabled && clickNodeOrParent(directCacheNode)) {
                        recordAndAdvance()
                        return true
                    } else if (!directCacheNode.isEnabled) {
                        skipAndAdvance()
                        return true
                    }
                }

                // 2) Standard Android / Samsung "Storage" / "Yaddaş" row
                val storageNode = findMatchingNode(root, storageKeywords, emptyList())
                if (storageNode != null && clickNodeOrParent(storageNode)) {
                    stepState = 1
                    scheduleStepTimeout(2000L)
                    return true
                }

                // 3) Xiaomi MIUI / HyperOS bottom bar "Məlumatları təmizlə" / "Clear data" menu trigger
                val miuiTrigger = findMatchingNode(root, miuiMenuKeywords, miuiMenuForbidden)
                if (miuiTrigger != null && miuiTrigger.isEnabled && clickNodeOrParent(miuiTrigger)) {
                    stepState = 2
                    scheduleStepTimeout(1800L)
                    return true
                }
            }

            1, 2 -> {
                // Inside Storage sub-screen OR MIUI bottom sheet: ONLY click safe "Clear cache"
                val cacheNode = findMatchingNode(root, clearCacheKeywords, strictDataForbidden)
                if (cacheNode != null) {
                    if (cacheNode.isEnabled && clickNodeOrParent(cacheNode)) {
                        // On MIUI a small confirmation dialog ("Keş təmizlənsin? -> OK") sometimes appears
                        if (stepState == 2) {
                            stepState = 3
                            recordSuccessOnly()
                            scheduleStepTimeout(900L)
                        } else {
                            recordAndAdvance()
                        }
                        return true
                    } else if (!cacheNode.isEnabled) {
                        skipAndAdvance()
                        return true
                    }
                }
            }

            3 -> {
                // Optional confirmation dialog after clicking "Clear cache" on MIUI
                val okNode = findExactOrButtonNode(root, confirmKeywords)
                if (okNode != null && okNode.isEnabled && clickNodeOrParent(okNode)) {
                    currentIndex++
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    handler.postDelayed({ processNext() }, 280L)
                    return true
                }
            }
        }
        return false
    }

    private fun recordSuccessOnly() {
        if (currentIndex < queue.size) {
            val target = queue[currentIndex]
            cleanedCount++
            clearedBytes += target.cacheBytes
        }
    }

    private fun recordAndAdvance() {
        recordSuccessOnly()
        currentIndex++
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        handler.postDelayed({ processNext() }, 320L)
    }

    private fun skipAndAdvance() {
        currentIndex++
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        handler.postDelayed({ processNext() }, 180L)
    }

    private fun findMatchingNode(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        forbidden: List<String>
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase().trim()
            val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
            val combined = "$text $desc".trim()

            if (combined.isNotEmpty()) {
                val hasForbidden = forbidden.any { combined.contains(it) }
                if (!hasForbidden && keywords.any { kw -> combined.contains(kw) }) {
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun findExactOrButtonNode(
        root: AccessibilityNodeInfo,
        labels: List<String>
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()
            val text = (node.text?.toString() ?: "").lowercase().trim()
            val desc = (node.contentDescription?.toString() ?: "").lowercase().trim()
            if (text in labels || desc in labels) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.add(it) }
            }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.isEnabled) {
                if (cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            cur = cur.parent
            depth++
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
