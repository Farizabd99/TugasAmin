package com.example.phonebilling.calling

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WartelAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WartelAccessibility"
        
        // Target tipe panggilan: "VOICE", "VIDEO", "CONFIRM" atau null
        @Volatile
        var targetCallType: String? = null

        // Callback untuk memberi tahu helper ketika panggilan berhasil dipicu
        var onCallTriggered: (() -> Unit)? = null

        /**
         * Helper untuk menulis log ke file cache lokal agar bisa ditarik lewat ADB run-as.
         */
        fun logToFile(context: android.content.Context, message: String) {
            try {
                val file = File(context.cacheDir, "accessibility_debug.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val timestamp = sdf.format(Date())
                file.appendText("[$timestamp] $message\n")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log to file", e)
            }
        }
        
        /**
         * Bersihkan file log debug.
         */
        fun clearLog(context: android.content.Context) {
            try {
                val file = File(context.cacheDir, "accessibility_debug.txt")
                if (file.exists()) file.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear log file", e)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val callType = targetCallType
        val pkg = event.packageName?.toString()
        val eventTypeStr = AccessibilityEvent.eventTypeToString(event.eventType)
        
        // Catat semua event WhatsApp ke file log
        if (pkg == "com.whatsapp") {
            logToFile(applicationContext, "WA Event: $eventTypeStr, targetCallType=$callType")
        }
        
        Log.i(TAG, "onAccessibilityEvent: eventType=$eventTypeStr, targetCallType=$callType, package=$pkg")
        
        // Hanya proses event yang berasal dari WhatsApp
        if (pkg != "com.whatsapp") return
        if (callType == null) return

        // Proses saat jendela berubah atau konten di dalamnya berubah
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            val rootNode = getWhatsAppRootNode()
            if (rootNode == null) return
            
            try {
                if (callType == "VOICE" || callType == "VIDEO") {
                    // 1. Cari dan klik tombol panggil utama di chat screen
                    if (findAndClickCallButton(rootNode, callType)) {
                        logToFile(applicationContext, "SUCCESS: Clicked main WhatsApp $callType button!")
                        android.widget.Toast.makeText(applicationContext, "Menghubungkan $callType...", android.widget.Toast.LENGTH_SHORT).show()
                        
                        // Transisi ke status konfirmasi dialog
                        targetCallType = "CONFIRM"
                        onCallTriggered?.invoke()
                    }
                } else if (callType == "CONFIRM") {
                    // 2. Cari dan klik tombol konfirmasi ("Telepon" / "Panggil" / "Call")
                    if (findAndClickConfirmButton(rootNode)) {
                        logToFile(applicationContext, "SUCCESS: Clicked WhatsApp confirmation button!")
                        android.widget.Toast.makeText(applicationContext, "Panggilan dimulai!", android.widget.Toast.LENGTH_SHORT).show()
                        targetCallType = null // Reset target panggilan
                    }
                }
            } finally {
                rootNode.recycle()
            }
        }
    }

    /**
     * Mengambil root node WhatsApp secara aman.
     * Jika rootInActiveWindow bukan milik WhatsApp (misal karena tertutup overlay focus),
     * maka akan dicari di daftar window interaktif.
     */
    private fun getWhatsAppRootNode(): AccessibilityNodeInfo? {
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            if (activeRoot.packageName == "com.whatsapp") {
                return activeRoot
            }
            activeRoot.recycle()
        }
        
        // Fallback: Cari di semua window interaktif
        try {
            val activeWindows = windows
            if (activeWindows != null) {
                for (window in activeWindows) {
                    val r = window.root
                    if (r != null) {
                        if (r.packageName == "com.whatsapp") {
                            logToFile(applicationContext, "Found WhatsApp root via windows fallback!")
                            return r
                        }
                        r.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            logToFile(applicationContext, "Error retrieving windows fallback: ${e.message}")
        }
        return null
    }

    private fun findAndClickCallButton(node: AccessibilityNodeInfo, callType: String): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase()
        val text = node.text?.toString()?.lowercase()
        
        if (desc != null || text != null) {
            val nodeClass = node.className?.toString() ?: ""
            logToFile(applicationContext, "Node check: class=$nodeClass, desc='$desc', text='$text'")
            
            val containsTarget = if (callType == "VIDEO") {
                (desc != null && (desc.contains("video call") || desc.contains("panggilan video") || desc.contains("telepon video"))) ||
                (text != null && (text.contains("video call") || text.contains("panggilan video") || text.contains("telepon video")))
            } else {
                (desc != null && (desc.contains("voice call") || desc.contains("panggilan suara") || desc.contains("telepon suara") || desc.contains("telepon")) && !desc.contains("video")) ||
                (text != null && (text.contains("voice call") || text.contains("panggilan suara") || text.contains("telepon suara") || text.contains("telepon")) && !text.contains("video"))
            }

            // Hindari pencocokan dengan teks info enkripsi obrolan
            val isExcluded = (desc != null && (desc.contains("terenkripsi") || desc.contains("pesan") || desc.contains("end-to-end") || desc.contains("obrolan"))) ||
                            (text != null && (text.contains("terenkripsi") || text.contains("pesan") || text.contains("end-to-end") || text.contains("obrolan")))

            val isMatch = containsTarget && !isExcluded

            if (isMatch) {
                logToFile(applicationContext, "MATCH FOUND: desc='$desc', text='$text'. Attempting click...")
                Log.i(TAG, "Found target button match: desc='$desc', text='$text'. Attempting to click...")
                
                // Cari node yang clickable
                var clickableNode = node
                var depth = 0
                while (clickableNode != null && !clickableNode.isClickable && depth < 5) {
                    clickableNode = clickableNode.parent
                    depth++
                }
                
                if (clickableNode != null && clickableNode.isClickable) {
                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    logToFile(applicationContext, "Perform ACTION_CLICK on class=${clickableNode.className} result: $clicked")
                    Log.i(TAG, "Perform ACTION_CLICK result: $clicked")
                    return clicked
                } else {
                    logToFile(applicationContext, "Match found but no clickable ancestor in 5 levels.")
                }
            }
        }

        // Cari di anak-anaknya (recursive)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickCallButton(child, callType)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun findAndClickConfirmButton(node: AccessibilityNodeInfo): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase()?.trim()
        val text = node.text?.toString()?.lowercase()?.trim()
        
        if (desc != null || text != null) {
            val nodeClass = node.className?.toString() ?: ""
            logToFile(applicationContext, "Confirm Node check: class=$nodeClass, desc='$desc', text='$text'")
            
            // Cari kata eksak untuk tombol persetujuan panggilan WhatsApp
            val isMatch = desc == "telepon" || desc == "panggil" || desc == "call" ||
                          text == "telepon" || text == "panggil" || text == "call"
                          
            if (isMatch) {
                logToFile(applicationContext, "CONFIRM MATCH FOUND: desc='$desc', text='$text'. Attempting click...")
                Log.i(TAG, "Found confirmation button match: desc='$desc', text='$text'. Attempting to click...")
                
                var clickableNode = node
                var depth = 0
                while (clickableNode != null && !clickableNode.isClickable && depth < 5) {
                    clickableNode = clickableNode.parent
                    depth++
                }
                
                if (clickableNode != null && clickableNode.isClickable) {
                    val clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    logToFile(applicationContext, "Perform ACTION_CLICK on confirm button result: $clicked")
                    Log.i(TAG, "Perform ACTION_CLICK on confirm button result: $clicked")
                    return clicked
                } else {
                    logToFile(applicationContext, "Confirm match found but no clickable ancestor.")
                }
            }
        }

        // Cari di anak-anaknya (recursive)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickConfirmButton(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    override fun onInterrupt() {
        logToFile(applicationContext, "Accessibility Service Interrupted")
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        logToFile(applicationContext, "Wartel Accessibility Service Connected!")
        Log.i(TAG, "Wartel Accessibility Service Connected!")
    }
}
