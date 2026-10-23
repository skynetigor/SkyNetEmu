package com.winlator.cmod.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.hardware.input.InputManager
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/** UI windows only: never install on the guest display/input window. */
object GamepadNavigation {
    val inUse = mutableStateOf(false)
    private val views = WeakHashMap<View, ViewInput>()

    @JvmStatic
    fun install(window: Window): AutoCloseable {
        val current = window.callback
        val router = if (current is Router) current else Router(current, window.decorView).also { window.callback = it }
        router.owners++
        var closed = false
        return AutoCloseable {
            if (closed) return@AutoCloseable
            closed = true
            router.owners--
            if (router.owners == 0) {
                router.close()
                if (window.callback === router) window.callback = router.wrapped
            }
        }
    }

    @Composable
    fun Bind() {
        val view = LocalView.current
        val window = generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            ?: activity(view.context)?.window
        DisposableEffect(window) {
            val binding = window?.let(::install)
            onDispose { binding?.close() }
        }
        DisposableEffect(view) {
            val input = views.getOrPut(view) { ViewInput(view) }
            input.owners++
            onDispose {
                input.owners--
                if (input.owners == 0) {
                    input.close()
                    views.remove(view)
                }
            }
        }
    }

    private fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }

    private class Joystick(private val view: View, private val dispatch: (KeyEvent) -> Boolean) {
        private val handler = Handler(Looper.getMainLooper())
        private var direction = 0
        private var deviceId = 0
        private val manager = view.context.getSystemService(Context.INPUT_SERVICE) as InputManager
        private val deviceListener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(id: Int) = Unit
            override fun onInputDeviceChanged(id: Int) { if (id == deviceId) stop() }
            override fun onInputDeviceRemoved(id: Int) { if (id == deviceId) stop() }
        }
        init { manager.registerInputDeviceListener(deviceListener, handler) }
        private val repeat = object : Runnable {
            override fun run() {
                if (direction == 0) return
                sendDirection()
                handler.postDelayed(this, 110)
            }
        }

        fun stop() {
            direction = 0
            handler.removeCallbacks(repeat)
        }

        fun close() {
            stop()
            manager.unregisterInputDeviceListener(deviceListener)
        }

        fun motion(event: MotionEvent): Boolean {
            if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.action != MotionEvent.ACTION_MOVE) return false
            val x = axis(event, MotionEvent.AXIS_HAT_X).takeIf { it != 0f } ?: axis(event, MotionEvent.AXIS_X)
            val y = axis(event, MotionEvent.AXIS_HAT_Y).takeIf { it != 0f } ?: axis(event, MotionEvent.AXIS_Y)
            val next = UiNavigationPolicy.stickDirection(x, y)
            if (next != direction || event.deviceId != deviceId) {
                stop()
                direction = next
                deviceId = event.deviceId
                if (next != 0) {
                    inUse.value = true
                    sendDirection()
                    handler.postDelayed(repeat, 350)
                }
            }
            return true
        }

        private fun axis(event: MotionEvent, axis: Int): Float {
            val value = event.getAxisValue(axis)
            val flat = event.device?.getMotionRange(axis, event.source)?.flat ?: 0f
            return if (kotlin.math.abs(value) <= maxOf(flat, .2f)) 0f else value
        }

        private fun sendDirection() {
            if (!view.hasWindowFocus()) { stop(); return }
            val time = SystemClock.uptimeMillis()
            for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                dispatch(KeyEvent(time, time, action, direction, 0, 0, deviceId, 0, 0, InputDevice.SOURCE_DPAD))
            }
        }
    }

    private class ViewInput(private val view: View) {
        var owners = 0
        private val joystick = Joystick(view, view::dispatchKeyEvent)
        init {
            // Compose popups have their own ViewRoot, not the Activity's Window.Callback.
            view.setOnGenericMotionListener { _, event -> joystick.motion(event) }
            view.setOnKeyListener { _, code, event ->
                if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_DPAD)) {
                    inUse.value = true
                }
                val mapped = UiNavigationPolicy.confirmOrBack(code)
                if (mapped == code) false else view.dispatchKeyEvent(remap(event, mapped))
            }
        }
        fun close() {
            joystick.close()
            view.setOnGenericMotionListener(null)
            view.setOnKeyListener(null)
        }
    }

    private fun remap(event: KeyEvent, code: Int) = KeyEvent(
        event.downTime, event.eventTime, event.action, code, event.repeatCount,
        event.metaState, event.deviceId, event.scanCode, event.flags, InputDevice.SOURCE_DPAD)

    private class Router(callback: Window.Callback, view: View) : WindowCallbackWrapper(callback) {
        var owners = 0
        private val joystick = Joystick(view, ::dispatchKeyEvent)

        fun close() = joystick.close()

        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) joystick.stop()
            super.onWindowFocusChanged(hasFocus)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
            joystick.motion(event) || super.dispatchGenericMotionEvent(event)

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                inUse.value = false
                joystick.stop()
            }
            return super.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val controller = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
                event.keyCode in KeyEvent.KEYCODE_BUTTON_A..KeyEvent.KEYCODE_BUTTON_MODE
            if (controller || event.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_CENTER) {
                inUse.value = true
            }
            if (!controller) return super.dispatchKeyEvent(event)
            val code = UiNavigationPolicy.confirmOrBack(event.keyCode)
            val mapped = if (code == event.keyCode) event else remap(event, code)
            return super.dispatchKeyEvent(mapped)
        }
    }
}
