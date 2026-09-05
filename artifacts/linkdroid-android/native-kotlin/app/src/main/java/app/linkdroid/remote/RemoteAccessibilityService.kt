package app.linkdroid.remote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        private var activeService: RemoteAccessibilityService? = null

        fun execute(command: RemoteCommand, callback: (Boolean, String?) -> Unit) {
            val service = activeService
            if (service == null) {
                callback(false, "ACCESSIBILITY_SERVICE_DISABLED")
            } else {
                service.executeCommand(command, callback)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun tap(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun executeCommand(command: RemoteCommand, callback: (Boolean, String?) -> Unit) {
        when (command) {
            is RemoteCommand.Tap -> dispatchGesture(
                gesture = gesture(Path().apply {
                    moveTo(
                        command.x * resources.displayMetrics.widthPixels,
                        command.y * resources.displayMetrics.heightPixels,
                    )
                }, command.durationMs),
                callback = callback,
            )
            is RemoteCommand.Swipe -> dispatchGesture(
                gesture = gesture(Path().apply {
                    moveTo(
                        command.startX * resources.displayMetrics.widthPixels,
                        command.startY * resources.displayMetrics.heightPixels,
                    )
                    lineTo(
                        command.endX * resources.displayMetrics.widthPixels,
                        command.endY * resources.displayMetrics.heightPixels,
                    )
                }, command.durationMs),
                callback = callback,
            )
            is RemoteCommand.Text -> {
                val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused == null) {
                    callback(false, "NO_TEXT_FIELD_FOCUSED")
                } else {
                    val arguments = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            command.value,
                        )
                    }
                    callback(
                        focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments),
                        null,
                    )
                }
            }
            RemoteCommand.Back -> callback(performGlobalAction(GLOBAL_ACTION_BACK), null)
            RemoteCommand.Home -> callback(performGlobalAction(GLOBAL_ACTION_HOME), null)
        }
    }

    private fun gesture(path: Path, durationMs: Long): GestureDescription =
        GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()

    private fun dispatchGesture(
        gesture: GestureDescription,
        callback: (Boolean, String?) -> Unit,
    ) {
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(true, null)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(false, "GESTURE_CANCELLED")
                }
            },
            null,
        )
        if (!dispatched) callback(false, "GESTURE_NOT_DISPATCHED")
    }
}