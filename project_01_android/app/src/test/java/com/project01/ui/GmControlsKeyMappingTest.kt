package com.project01.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the presenter HID key → GM action mapping. Runs on a plain JVM:
 * `presenterActionFor` only reads inlined `KeyEvent.KEYCODE_*` constants, so no
 * Robolectric activity/binding is needed. Keycodes for the Norwii N21 BLE were
 * captured from a real device via `adb logcat`.
 */
class GmControlsKeyMappingTest {

    @Test
    fun `page-forward keys map to NEXT`() {
        assertEquals(PresenterAction.NEXT, presenterActionFor(KeyEvent.KEYCODE_DPAD_RIGHT, ctrlPressed = false))
        assertEquals(PresenterAction.NEXT, presenterActionFor(KeyEvent.KEYCODE_PAGE_DOWN, ctrlPressed = false))
        assertEquals(PresenterAction.NEXT, presenterActionFor(KeyEvent.KEYCODE_MEDIA_NEXT, ctrlPressed = false))
    }

    @Test
    fun `page-back keys map to PREVIOUS`() {
        assertEquals(PresenterAction.PREVIOUS, presenterActionFor(KeyEvent.KEYCODE_DPAD_LEFT, ctrlPressed = false))
        assertEquals(PresenterAction.PREVIOUS, presenterActionFor(KeyEvent.KEYCODE_PAGE_UP, ctrlPressed = false))
        assertEquals(PresenterAction.PREVIOUS, presenterActionFor(KeyEvent.KEYCODE_MEDIA_PREVIOUS, ctrlPressed = false))
    }

    @Test
    fun `Norwii N21 Mark button (Ctrl+P) toggles the light`() {
        assertEquals(PresenterAction.TOGGLE_LIGHT, presenterActionFor(KeyEvent.KEYCODE_P, ctrlPressed = true))
    }

    @Test
    fun `bare P without Ctrl is not a presenter control`() {
        assertNull(presenterActionFor(KeyEvent.KEYCODE_P, ctrlPressed = false))
    }

    @Test
    fun `the lone Ctrl-down the Mark button fires first is not itself a control`() {
        assertNull(presenterActionFor(KeyEvent.KEYCODE_CTRL_LEFT, ctrlPressed = true))
    }

    @Test
    fun `select-style keys toggle the light`() {
        assertEquals(PresenterAction.TOGGLE_LIGHT, presenterActionFor(KeyEvent.KEYCODE_F5, ctrlPressed = false))
        assertEquals(PresenterAction.TOGGLE_LIGHT, presenterActionFor(KeyEvent.KEYCODE_SPACE, ctrlPressed = false))
        assertEquals(PresenterAction.TOGGLE_LIGHT, presenterActionFor(KeyEvent.KEYCODE_ENTER, ctrlPressed = false))
        assertEquals(PresenterAction.TOGGLE_LIGHT, presenterActionFor(KeyEvent.KEYCODE_DPAD_CENTER, ctrlPressed = false))
        assertEquals(PresenterAction.TOGGLE_LIGHT, presenterActionFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, ctrlPressed = false))
    }

    @Test
    fun `volume keys stay unmapped so they do not fight the phone's media volume`() {
        assertNull(presenterActionFor(KeyEvent.KEYCODE_VOLUME_UP, ctrlPressed = false))
        assertNull(presenterActionFor(KeyEvent.KEYCODE_VOLUME_DOWN, ctrlPressed = false))
    }
}
