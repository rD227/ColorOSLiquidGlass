package net.z841973620.colorosliquidglass.glass

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import net.z841973620.colorosliquidglass.GlassConfig
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropClient
import net.z841973620.colorosliquidglass.ipc.SharedFrameArena
import net.z841973620.colorosliquidglass.ipc.DesktopBackdropHub
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic lifecycle transitions; does not manipulate the real volume dialog or audio devices. */
fun main() {
    if (Looper.myLooper() == null) Looper.prepareMainLooper()
    val threadClass = Class.forName("android.app.ActivityThread")
    val thread = threadClass.getMethod("systemMain").invoke(null)
    val context = threadClass.getMethod("getSystemContext").invoke(thread) as Context
    val config = GlassConfig()
    val type = VolumePanelGlass::class.java
    val pending = type.getDeclaredField("PENDING_APPLIES").apply { isAccessible = true }
        .get(null) as Map<*, *>
    val handler = type.getDeclaredField("MAIN").apply { isAccessible = true }.get(null) as Handler
    val dialog = Any()
    VolumePanelGlass.schedule(dialog, config)
    val first = pending[dialog] as Runnable
    check(handler.hasCallbacks(first))
    VolumePanelGlass.schedule(dialog, config)
    val second = pending[dialog] as Runnable
    check(first !== second && !handler.hasCallbacks(first) && handler.hasCallbacks(second))
    VolumePanelGlass.release(dialog)
    check(pending.isEmpty() && !handler.hasCallbacks(second)) {
        "Dismissed dialog still owns delayed installs"
    }
    // Switching to another dialog must not resurrect callbacks from the old one.
    val nextDialog = Any()
    VolumePanelGlass.schedule(nextDialog, config)
    val last = pending[nextDialog] as Runnable
    repeat(5) { last.run() }
    check(pending.isEmpty()) { "Completed apply sequence retained its dialog" }
    handler.removeCallbacks(last)
    println("PASS: apply coalescing, dismiss cancellation, replacement dialog, completed callback cleanup")

    val host = object : View(context) {
        override fun isAttachedToWindow() = true
    }
    val capture = BehindDisplayCapture::class.java
    val state = capture.getDeclaredMethod("stateOf", View::class.java)
        .apply { isAccessible = true }.invoke(null, host)
    val stateType = state.javaClass
    val menuRegion = stateType.getDeclaredField("menuRegion").apply { isAccessible = true }
        .get(state) as Rect
    menuRegion.set(0, 0, 32, 64)
    val opened = stateType.getDeclaredField("sessionOpen").apply { isAccessible = true }
    val open = capture.getDeclaredMethod("openSessionIfNeeded", View::class.java, stateType)
        .apply { isAccessible = true }
    check(!DesktopBackdropClient.isReady())
    open.invoke(null, host, state)
    check(!opened.getBoolean(state)) { "An unavailable connection claimed a LIVE reference" }

    // Use a private arena in this probe process; never connect to Launcher's actual producer.
    val arenaField = DesktopBackdropClient::class.java.getDeclaredField("arena").apply { isAccessible = true }
    val arena = SharedFrameArena.createProducer()
    arenaField.set(null, arena)
    try {
        open.invoke(null, host, state)
        check(opened.getBoolean(state)) { "A ready connection did not acquire LIVE" }
        val refs = DesktopBackdropClient::class.java.getDeclaredField("sessionRefs").apply { isAccessible = true }
        check(refs.getInt(null) == 1)
        open.invoke(null, host, state)
        check(refs.getInt(null) == 1) { "Repeated binding acquired a duplicate LIVE reference" }

        val ownerB = Any()
        @Suppress("UNCHECKED_CAST")
        val installed = type.getDeclaredField("INSTALLED").apply { isAccessible = true }
            .get(null) as MutableMap<View, WeakReference<Any>>
        installed[host] = WeakReference(ownerB)
        BehindDisplayCapture.tagVolumeHost(host)
        VolumePanelGlass.release(dialog)
        check(installed[host]?.get() === ownerB && refs.getInt(null) == 1) {
            "Old dialog dismissal removed the new dialog's host/session"
        }

        val oldEpoch = DesktopBackdropClient.sessionEpoch()
        DesktopBackdropClient.forceCloseSession()
        val newEpoch = DesktopBackdropClient.sessionEpoch()
        check(DesktopBackdropClient.tryOpenSession(menuRegion, oldEpoch) == -1)
        check(refs.getInt(null) == 0)
        check(DesktopBackdropClient.tryOpenSession(menuRegion, newEpoch) == newEpoch)
        DesktopBackdropClient.closeSession(oldEpoch)
        check(refs.getInt(null) == 1) { "Stale close stole the new generation's reference" }
        DesktopBackdropClient.closeSession(newEpoch)
        check(refs.getInt(null) == 0)
        // The host still owns the invalidated epoch; release must not affect another owner.
        BehindDisplayCapture.stopStreaming(host)
        check(refs.getInt(null) == 0 && !opened.getBoolean(state))
        installed.clear()
        println("PASS: late old-dialog dismissal and atomic epoch-scoped acquisition/release")

        val hub = DesktopBackdropHub::class.java
        val producerArena = hub.getDeclaredField("arena").apply { isAccessible = true }
        producerArena.set(null, arena)
        val busy = hub.getDeclaredField("publishBusyIdx").apply { isAccessible = true }
            .get(null) as AtomicInteger
        val applySession = hub.getDeclaredMethod("applySession", SharedFrameArena::class.java,
            Int::class.javaPrimitiveType, Rect::class.java).apply { isAccessible = true }
        val finishRelease = hub.getDeclaredMethod("releasePlatesOnMain").apply { isAccessible = true }
        val crop = Rect(10, 20, 42, 84)
        applySession.invoke(null, arena, SharedFrameArena.FLAG_LIVE, crop)
        busy.set(0) // Hold a frame in the publisher while STOP is processed.
        applySession.invoke(null, arena, SharedFrameArena.FLAG_STOP, Rect())
        check(busy.get() == 0) { "STOP forgot the publisher's borrowed buffer" }
        applySession.invoke(null, arena, SharedFrameArena.FLAG_LIVE, crop)
        busy.set(-1)
        finishRelease.invoke(null) // Deliver the old completion after the new LIVE.
        check(DesktopBackdropSampler.liveCrop() == crop) { "Stale cleanup erased the new crop" }
        applySession.invoke(null, arena, SharedFrameArena.FLAG_STOP, Rect())
        check(DesktopBackdropSampler.liveCrop().isEmpty)
        producerArena.set(null, null)
        DesktopBackdropHub.stop()
        println("PASS: busy publisher STOP -> same-crop LIVE -> old cleanup retains the new crop")
    } finally {
        arenaField.set(null, null)
        arena.close()
    }
    println("PASS: unavailable connection stays closed, ready connection opens once, stop releases LIVE")
}
