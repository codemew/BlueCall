package com.codemew.bluecall.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PalettesTest {

    @Test
    fun idsAreUniqueAndIncludeDynamicAndCustom() {
        val ids = Palettes.choiceIds
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(Palettes.DYNAMIC, ids.first())
        assertEquals(Palettes.CUSTOM, ids.last())
        assertTrue("many colour choices", ids.size >= 14)
    }

    @Test
    fun everyPaletteHasFourLiquidColours() {
        Palettes.presets.forEach { assertEquals(it.id, 4, it.liquid.size) }
        assertEquals(4, Palettes.custom(123f).liquid.size)
    }

    @Test
    fun unknownIdFallsBackToPhoneColours() {
        assertEquals(Palettes.DYNAMIC, Palettes.resolveId("no-such-theme"))
        assertEquals("ocean", Palettes.resolveId("ocean"))
        assertEquals(Palettes.CUSTOM, Palettes.resolveId(Palettes.CUSTOM))
    }

    @Test
    fun customHueChangesTheColours() {
        assertTrue(Palettes.custom(10f).seed != Palettes.custom(200f).seed)
    }
}
