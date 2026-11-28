package dev.cinematic.p0.photoroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureDesign
import dev.cinematic.p0.CaptureTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DevelopSaveRowAndroidTest {
    @get:Rule val fixture = SyntheticFixtureRule()

    @Test fun compressedSaveRowChangesLayoutAtMeasuredLabelWidth() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        for (shortfallPx in 0..1) {
            val savePositioned = CountDownLatch(1)
            val switchPositioned = CountDownLatch(1)
            var saveBounds = Rect.Zero
            var switchBounds = Rect.Zero
            var rowWidthPx = 0
            var inlineWidthPx = 0
            fixture.content = {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1f)) {
                    CaptureTheme {
                        val measurer = rememberTextMeasurer()
                        val labelWidthPx = listOf("表示", "比較").maxOf {
                            measurer.measure(it, style = CaptureDesign.Label, maxLines = 1, softWrap = false).size.width
                        }
                        // 各ボタンの左右 2px と、スイッチ外周の左右 2px を含める。
                        inlineWidthPx = (labelWidthPx - shortfallPx + 4) * 2 + 4
                        rowWidthPx = 12 * 2 + 88 + 76 + inlineWidthPx
                        Box(Modifier.width(rowWidthPx.dp)) {
                            DevelopSaveRow(
                                cancelAndSave = {
                                    Box(Modifier.width(88.dp).height(48.dp))
                                    Box(Modifier.width(76.dp).height(76.dp).onGloballyPositioned {
                                        saveBounds = it.boundsInRoot()
                                        savePositioned.countDown()
                                    })
                                },
                                displayCompareSwitch = { width ->
                                    Box(Modifier.width(width).height(48.dp).onGloballyPositioned {
                                        switchBounds = it.boundsInRoot()
                                        switchPositioned.countDown()
                                    })
                                },
                            )
                        }
                    }
                }
            }
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                assertTrue("保存ボタンが配置される", savePositioned.await(5, TimeUnit.SECONDS))
                assertTrue("スイッチが配置される", switchPositioned.await(5, TimeUnit.SECONDS))
                assertTrue("保存行が 288dp 未満", rowWidthPx < 288)
                if (shortfallPx == 0) {
                    assertEquals(inlineWidthPx.toFloat(), switchBounds.width, 0f)
                    assertEquals(saveBounds.center.y, switchBounds.center.y, 0f)
                } else {
                    assertEquals((rowWidthPx - 24).toFloat(), switchBounds.width, 0f)
                    assertTrue("スイッチは保存ボタンより下に配置される", switchBounds.top >= saveBounds.bottom)
                }
            }
        }
    }
}
