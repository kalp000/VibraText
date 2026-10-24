package com.example.vibratext.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.vibratext.app.vibration.AndroidVibrationSink
import com.example.vibratext.data.EncodingProfile
import com.example.vibratext.data.VibrationUsage
import com.example.vibratext.encoding.VibSymbol
import com.example.vibratext.pipeline.ManualPlayback
import com.example.vibratext.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests of the real vibrator path. They check that playing, stopping and replaying work
 * without errors and leave the controller idle; whether the motor really buzzed has to be felt by
 * a person (use the Test screen), and an emulator may have no vibrator at all, in which case the
 * tests are skipped.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sink = AndroidVibrationSink(context) { VibrationUsage.NOTIFICATION }
    private val controller = PlaybackController(scope, sink)
    private val profile = EncodingProfile(id = "p", name = "P")

    @After
    fun tearDown() {
        controller.stop()
        scope.cancel()
    }

    @Test
    fun cancelIsAlwaysSafe() {
        sink.cancel()
        sink.cancel()
    }

    @Test
    fun aSymbolPlaysToCompletionAndTheControllerGoesIdle() {
        assumeTrue(sink.isAvailable())
        val request = ManualPlayback.forSymbol(VibSymbol.SHORT, profile)
        controller.submit(request)
        assertTrue(controller.state.value.isBusy)
        Thread.sleep(request.script.totalDurationMs + 600)
        assertTrue(controller.isIdle)
    }

    @Test
    fun stopSilencesAndResetsImmediately() {
        assumeTrue(sink.isAvailable())
        val plan = ManualPlayback.forMessage(null, "hello world", profile)!!
        controller.submit(plan.request)
        Thread.sleep(300)
        assertTrue(controller.state.value.isBusy)
        controller.stop()
        assertTrue(controller.isIdle)
        assertEquals(false, controller.state.value.isBusy)
        // A stale job must not wake up and start vibrating again: the controller stays idle.
        Thread.sleep(1_500)
        assertTrue(controller.isIdle)
    }

    @Test
    fun aNewTestInterruptsTheCurrentOneWithoutOverlap() {
        assumeTrue(sink.isAvailable())
        val long = ManualPlayback.forMessage(null, "a long test message", profile)!!
        controller.submit(long.request)
        Thread.sleep(200)
        val short = ManualPlayback.forTick(profile)
        controller.submit(short)
        assertEquals(short.id, controller.state.value.requestId)
    }
}
