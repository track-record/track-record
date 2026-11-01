package com.trackrecord.desktop

import org.lwjgl.util.nfd.NativeFileDialog.NFD_Init
import org.lwjgl.util.nfd.NativeFileDialog.NFD_OKAY
import org.lwjgl.util.nfd.NativeFileDialog.NFD_Quit
import kotlin.test.Test
import kotlin.test.assertEquals

/** The system's file dialogs (DesktopPlatformUi) load: their native library is packaged for this platform. */
class NativeDialogsTest {
    @Test
    fun `the native dialogs start`() {
        assertEquals(NFD_OKAY, NFD_Init())
        NFD_Quit()
    }
}
