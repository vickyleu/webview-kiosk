package uk.nktnet.webviewkiosk.utils

import android.view.InputDevice
import android.view.MotionEvent
import android.util.Log
import android.webkit.WebView
import java.lang.ref.WeakReference

object WebViewMouseEventBridge {
    private const val TAG = "DoorplateMouseBridge"
    private var webViewRef: WeakReference<WebView>? = null
    internal var activeTouchDownTimeMs: Long = -1L
    internal var lastTouchEventTimeMs: Long = -1L
    internal var lastForwardedEventKey: String = ""

    fun bind(webView: WebView) {
        webViewRef = WeakReference(webView)
    }

    fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!event.isPointerSource()) {
            return false
        }
        val webView = webViewRef?.get() ?: return false
        if (event.isDuplicateForward()) {
            return true
        }
        lastTouchEventTimeMs = event.eventTime
        if (
            event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_MOVE &&
            event.actionMasked != MotionEvent.ACTION_UP &&
            event.actionMasked != MotionEvent.ACTION_CANCEL
        ) {
            return false
        }
        Log.d(TAG, event.describe("touch"))
        webView.dispatchMouseAsNativeTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> activeTouchDownTimeMs = event.downTime
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> activeTouchDownTimeMs = -1L
        }
        return true
    }

    fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!event.isPointerSource()) {
            return false
        }
        val webView = webViewRef?.get() ?: return false
        if (event.isDuplicateForward()) {
            return true
        }
        if (
            (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
                event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) &&
            event.belongsToActiveTouchGesture()
        ) {
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                Log.d(TAG, event.describe("generic"))
                if (!webView.dispatchGenericMotionEvent(event)) {
                    webView.scrollBy(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL).scaledScrollDelta().toInt(),
                        (-event.getAxisValue(MotionEvent.AXIS_VSCROLL)).scaledScrollDelta().toInt(),
                    )
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS,
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                Log.d(TAG, event.describe("generic"))
                webView.dispatchButtonAsNativeTouch(event)
                return true
            }
            else -> return false
        }
    }
}

private fun Float.scaledScrollDelta(): Float = this * 120f

private fun WebView.dispatchMouseAsNativeTouch(event: MotionEvent) {
    val action = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
        MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
        MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
        MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
        else -> return
    }
    val touchEvent = MotionEvent.obtain(
        event.downTime,
        event.eventTime,
        action,
        event.x.coerceIn(0f, width.toFloat()),
        event.y.coerceIn(0f, height.toFloat()),
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            0f
        } else {
            event.pressure.coerceAtLeast(1f)
        },
        event.size.takeIf { it > 0f } ?: 1f,
        event.metaState,
        event.xPrecision,
        event.yPrecision,
        event.deviceId,
        event.edgeFlags,
    )
    touchEvent.source = InputDevice.SOURCE_TOUCHSCREEN
    try {
        dispatchTouchEvent(touchEvent)
    } finally {
        touchEvent.recycle()
    }
}

private fun WebView.dispatchButtonAsNativeTouch(event: MotionEvent) {
    val action = when (event.actionMasked) {
        MotionEvent.ACTION_BUTTON_PRESS -> MotionEvent.ACTION_DOWN
        MotionEvent.ACTION_BUTTON_RELEASE -> MotionEvent.ACTION_UP
        else -> return
    }
    val downTime = if (action == MotionEvent.ACTION_DOWN) {
        event.eventTime
    } else {
        WebViewMouseEventBridge.activeTouchDownTimeMs.takeIf { it >= 0L } ?: event.downTime
    }
    if (action == MotionEvent.ACTION_DOWN) {
        WebViewMouseEventBridge.activeTouchDownTimeMs = downTime
    }
    val touchEvent = MotionEvent.obtain(
        downTime,
        event.eventTime,
        action,
        event.x.coerceIn(0f, width.toFloat()),
        event.y.coerceIn(0f, height.toFloat()),
        if (action == MotionEvent.ACTION_UP) 0f else 1f,
        event.size.takeIf { it > 0f } ?: 1f,
        event.metaState,
        event.xPrecision,
        event.yPrecision,
        event.deviceId,
        event.edgeFlags,
    )
    touchEvent.source = InputDevice.SOURCE_TOUCHSCREEN
    try {
        dispatchTouchEvent(touchEvent)
    } finally {
        touchEvent.recycle()
    }
    if (action == MotionEvent.ACTION_UP) {
        WebViewMouseEventBridge.activeTouchDownTimeMs = -1L
    }
}

private fun MotionEvent.isPointerSource(): Boolean {
    return isFromSource(InputDevice.SOURCE_MOUSE) ||
        isFromSource(InputDevice.SOURCE_TOUCHPAD) ||
        isFromSource(InputDevice.SOURCE_CLASS_POINTER)
}

private fun MotionEvent.describe(dispatcher: String): String {
    return "dispatcher=$dispatcher action=$actionMasked source=0x${source.toString(16)} " +
        "buttonState=$buttonState actionButton=$actionButton x=$x y=$y " +
        "vscroll=${getAxisValue(MotionEvent.AXIS_VSCROLL)} hscroll=${getAxisValue(MotionEvent.AXIS_HSCROLL)}"
}

private fun MotionEvent.isDuplicateForward(): Boolean {
    val key = "$eventTime:$actionMasked:$source:$actionButton:$buttonState:${x.toInt()}:${y.toInt()}"
    if (key == WebViewMouseEventBridge.lastForwardedEventKey) {
        return true
    }
    WebViewMouseEventBridge.lastForwardedEventKey = key
    return false
}

private fun MotionEvent.belongsToActiveTouchGesture(): Boolean {
    val activeDown = WebViewMouseEventBridge.activeTouchDownTimeMs
    if (activeDown >= 0 && downTime == activeDown) {
        return true
    }
    val recentTouchDelta = eventTime - WebViewMouseEventBridge.lastTouchEventTimeMs
    return activeDown >= 0 && recentTouchDelta in 0..300
}

private fun WebView.dispatchMouseEventToPage(event: MotionEvent) {
    val pointerType: String
    val mouseType: String
    val emitClick: Boolean
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            pointerType = "pointerdown"
            mouseType = "mousedown"
            emitClick = false
        }
        MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_HOVER_MOVE -> {
            pointerType = "pointermove"
            mouseType = "mousemove"
            emitClick = false
        }
        MotionEvent.ACTION_HOVER_ENTER -> {
            pointerType = "pointerover"
            mouseType = "mouseover"
            emitClick = false
        }
        MotionEvent.ACTION_HOVER_EXIT -> {
            pointerType = "pointerout"
            mouseType = "mouseout"
            emitClick = false
        }
        MotionEvent.ACTION_UP -> {
            pointerType = "pointerup"
            mouseType = "mouseup"
            emitClick = true
        }
        MotionEvent.ACTION_BUTTON_PRESS -> {
            pointerType = "pointerdown"
            mouseType = "mousedown"
            emitClick = false
        }
        MotionEvent.ACTION_BUTTON_RELEASE -> {
            pointerType = "pointerup"
            mouseType = "mouseup"
            emitClick = true
        }
        MotionEvent.ACTION_CANCEL -> {
            pointerType = "pointercancel"
            mouseType = ""
            emitClick = false
        }
        else -> return
    }
    val x = event.x.coerceIn(0f, width.toFloat())
    val y = event.y.coerceIn(0f, height.toFloat())
    val activeButton = if (
        event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
        event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    ) {
        event.actionButton
    } else {
        event.buttonState
    }
    val button = when {
        activeButton and MotionEvent.BUTTON_SECONDARY != 0 -> 2
        activeButton and MotionEvent.BUTTON_TERTIARY != 0 -> 1
        else -> 0
    }
    val buttons = if (event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) 0 else event.buttonState
    val clickSnippet = if (emitClick) "fireMouse('click', 0, 0);" else ""
    evaluateJavascript(
        """
        (function() {
          const x = $x;
          const y = $y;
          const target = document.elementFromPoint(x, y) || document.body || document.documentElement;
          if (!target) return;
          const hasPointer = !!window.PointerEvent;
          const base = {
            bubbles: true,
            cancelable: true,
            composed: true,
            view: window,
            clientX: x,
            clientY: y,
            screenX: x,
            screenY: y,
            button: $button,
            buttons: $buttons,
            pointerId: 1,
            pointerType: 'mouse',
            isPrimary: true
          };
          function firePrimary(pointerType, mouseType) {
            try {
              const event = hasPointer && pointerType
                ? new PointerEvent(pointerType, base)
                : new MouseEvent(mouseType || pointerType.replace('pointer', 'mouse'), base);
              target.dispatchEvent(event);
            } catch (e) {
              target.dispatchEvent(new MouseEvent(mouseType || pointerType.replace('pointer', 'mouse'), base));
            }
          }
          function fireMouse(type, button, buttons) {
            target.dispatchEvent(new MouseEvent(type, Object.assign({}, base, { button: button, buttons: buttons })));
          }
          firePrimary('$pointerType', '$mouseType');
          $clickSnippet
        })();
        """.trimIndent(),
        null
    )
}

private fun WebView.dispatchWheelEventToPage(event: MotionEvent) {
    val x = event.x.coerceIn(0f, width.toFloat())
    val y = event.y.coerceIn(0f, height.toFloat())
    val deltaY = -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120f
    val deltaX = -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120f
    evaluateJavascript(
        """
        (function() {
          const x = $x;
          const y = $y;
          const target = document.elementFromPoint(x, y) || document.body || document.documentElement;
          if (!target) return;
          target.dispatchEvent(new WheelEvent('wheel', {
            bubbles: true,
            cancelable: true,
            composed: true,
            view: window,
            clientX: x,
            clientY: y,
            screenX: x,
            screenY: y,
            deltaX: $deltaX,
            deltaY: $deltaY,
            deltaMode: 0
          }));
        })();
        """.trimIndent(),
        null
    )
}
