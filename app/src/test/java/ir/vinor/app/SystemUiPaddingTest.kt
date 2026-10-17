package ir.vinor.app

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class SystemUiPaddingTest {

    @Test
    fun threeButtonNavClearsStatusAndNavigationBars() {
        val pad = SystemUiPadding.resolve(
            barsLeft = 0,
            barsTop = 96,
            barsRight = 0,
            barsBottom = 144,
        )
        assertArrayEquals(intArrayOf(0, 96, 0, 144), pad)
    }

    @Test
    fun gestureNavUsesTheShorterNavigationInset() {
        val pad = SystemUiPadding.resolve(
            barsLeft = 0,
            barsTop = 84,
            barsRight = 0,
            barsBottom = 48,
        )
        assertArrayEquals(intArrayOf(0, 84, 0, 48), pad)
    }

    @Test
    fun keyboardReplacesTheNavigationInsetWhenTaller() {
        val open = SystemUiPadding.resolve(barsBottom = 144, imeBottom = 720)
        val closed = SystemUiPadding.resolve(barsBottom = 144, imeBottom = 0)
        assertArrayEquals(intArrayOf(0, 0, 0, 720), open)
        assertArrayEquals(intArrayOf(0, 0, 0, 144), closed)
    }

    @Test
    fun cutoutWinsWhenItExtendsPastTheStatusBar() {
        val pad = SystemUiPadding.resolve(
            barsTop = 60,
            cutoutTop = 110,
            barsLeft = 0,
            cutoutLeft = 40,
        )
        assertArrayEquals(intArrayOf(40, 110, 0, 0), pad)
    }

    @Test
    fun zeroInsetsOnOlderApisAddNoGap() {
        assertArrayEquals(intArrayOf(0, 0, 0, 0), SystemUiPadding.resolve(0, 0, 0, 0))
    }
}
