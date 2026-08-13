package io.github.soulxyz.xyprt.printer

import io.github.soulxyz.xyprt.printer.dither.InkRemoval
import org.junit.Assert.assertEquals
import org.junit.Test

class InkRemovalTest {
    @Test fun removesStrongRedAndBlueButKeepsBlack() {
        val red = 0xffff3030.toInt()
        val blue = 0xff3040e8.toInt()
        val black = 0xff202020.toInt()
        val gray = 0xff909090.toInt()
        val out = InkRemoval.apply(intArrayOf(red, blue, black, gray), removeRed = true, removeBlue = true)
        assertEquals(0xffffffff.toInt(), out[0])
        assertEquals(0xffffffff.toInt(), out[1])
        assertEquals(black, out[2])
        assertEquals(gray, out[3])
    }

    @Test fun eachColorCanBeControlledIndependently() {
        val red = 0xffff3030.toInt()
        val blue = 0xff3040e8.toInt()
        val out = InkRemoval.apply(intArrayOf(red, blue), removeRed = true, removeBlue = false)
        assertEquals(0xffffffff.toInt(), out[0])
        assertEquals(blue, out[1])
    }
}
