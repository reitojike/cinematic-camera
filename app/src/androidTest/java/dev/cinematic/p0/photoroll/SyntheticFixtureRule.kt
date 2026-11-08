package dev.cinematic.p0.photoroll

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** Reinstalls synthetic content after every host creation, including configuration changes. */
class SyntheticFixtureRule(private val hostClassName: String = PhotoRollActivity::class.java.name) : TestWatcher(), Application.ActivityLifecycleCallbacks {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    var content: (@Composable () -> Unit)? = null
    private var host: ComponentActivity? = null
    val automation get() = instrumentation.uiAutomation
    override fun starting(description: Description) {
        (instrumentation.targetContext.applicationContext as Application).registerActivityLifecycleCallbacks(this)
    }
    override fun finished(description: Description) {
        (instrumentation.targetContext.applicationContext as Application).unregisterActivityLifecycleCallbacks(this)
        host = null
        content = null
    }
    // Post-created runs after the Activity's own onCreate/setContent, not before it.
    override fun onActivityPostCreated(activity: Activity, state: Bundle?) {
        if (activity is ComponentActivity && activity.javaClass.name == hostClassName) {
            host = activity
            content?.let { fixture -> activity.setContent { fixture() } }
        }
    }
    /** Software View-tree evidence only; does not capture display composition or GPU rendering. */
    fun captureSoftware(): Bitmap {
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            // Resolve the current host here, rather than retaining a Window across recreation.
            val decor = requireNotNull(host) { "No synthetic fixture host" }.window.decorView
            check(decor.width > 0 && decor.height > 0) { "Synthetic fixture has not been laid out" }
            val drawn = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            try { decor.draw(Canvas(drawn)) } catch (t: Throwable) { drawn.recycle(); throw t }
            bitmap = drawn
        }
        return requireNotNull(bitmap)
    }
    fun fixtureRoot(): AccessibilityNodeInfo? {
        val root = automation.rootInActiveWindow
        fun systemUiAnr(node: AccessibilityNodeInfo?): Boolean = node != null &&
            (node.text?.toString() == "System UI isn't responding" ||
                (0 until node.childCount).any { systemUiAnr(node.getChild(it)) })
        check(!(root?.packageName == "android" && systemUiAnr(root))) {
            "Environment failure: System UI ANR obscures the synthetic fixture"
        }
        var focused = false
        instrumentation.runOnMainSync { focused = host?.window?.decorView?.hasWindowFocus() == true }
        return root?.takeIf { focused && it.packageName == instrumentation.targetContext.packageName }
    }
    fun failure(label: String) {
        val root = automation.rootInActiveWindow
        var state = "no host"
        instrumentation.runOnMainSync { host?.let {
            state = "focus=${it.window.decorView.hasWindowFocus()} decor=${it.window.decorView.width}x${it.window.decorView.height}"
        } }
        android.util.Log.e("SyntheticFixture", "Missing $label; $state; root=${root?.packageName}/${root?.className} children=${root?.childCount}")
    }
    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityResumed(a: Activity) = Unit
    override fun onActivityPaused(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
    override fun onActivityDestroyed(a: Activity) { if (host === a) host = null }
}
