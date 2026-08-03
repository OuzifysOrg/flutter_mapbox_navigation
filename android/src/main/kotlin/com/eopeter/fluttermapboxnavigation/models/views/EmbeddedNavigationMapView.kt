package com.eopeter.fluttermapboxnavigation.models.views

import android.app.Activity
import android.content.Context
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.eopeter.fluttermapboxnavigation.TurnByTurn
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView

class EmbeddedNavigationMapView(
    context: Context,
    activity: Activity,
    binding: NavigationActivityBinding,
    binaryMessenger: BinaryMessenger,
    vId: Int,
    args: Any?,
    accessToken: String
) : PlatformView, TurnByTurn(context, activity, binding, accessToken) {
    private val viewId: Int = vId
    private val messenger: BinaryMessenger = binaryMessenger
    private val arguments = args as Map<*, *>
    private var viewLifecycle: EmbeddedViewLifecycle? = null
    private var isDisposed = false

    override fun initFlutterChannelHandlers() {
        methodChannel = MethodChannel(messenger, "flutter_mapbox_navigation/${viewId}")
        eventChannel = EventChannel(messenger, "flutter_mapbox_navigation/${viewId}/events")
        super.initFlutterChannelHandlers()
    }

    fun initialize() {
        initFlutterChannelHandlers()
        initNavigation()
        // v2 disabled Drop-In's long-press intercept here. Drop-In is gone in
        // v3 and the embedded view registers no long-press handler, so there
        // is nothing to opt out of any more.
    }

    override fun getView(): View {
        return binding.root
    }

    override fun onFlutterViewAttached(flutterView: View) {
        if (isDisposed || viewLifecycle != null) return

        val lifecycleOwner = activity as? LifecycleOwner ?: return
        viewLifecycle = EmbeddedViewLifecycle(lifecycleOwner.lifecycle).also {
            binding.mapView.setViewTreeLifecycleOwner(it)
        }
    }

    override fun onFlutterViewDetached() {
        clearViewLifecycle()
    }

    override fun dispose() {
        if (isDisposed) return
        isDisposed = true

        methodChannel?.setMethodCallHandler(null)
        eventChannel?.setStreamHandler(null)
        unregisterObservers()
        clearViewLifecycle()
    }

    private fun clearViewLifecycle() {
        viewLifecycle?.dispose()
        viewLifecycle = null
        binding.mapView.setViewTreeLifecycleOwner(null)
    }

    /**
     * A platform view is shorter-lived than its FlutterActivity. Giving the
     * MapView its own lifecycle lets Maps release the render surface when the
     * Flutter widget is removed instead of waiting for the activity to die.
     */
    private class EmbeddedViewLifecycle(
        private val parentLifecycle: Lifecycle
    ) : LifecycleOwner, DefaultLifecycleObserver {
        private val registry = LifecycleRegistry(this)

        init {
            parentLifecycle.addObserver(this)
        }

        override val lifecycle: Lifecycle
            get() = registry

        override fun onCreate(owner: LifecycleOwner) {
            registry.currentState = Lifecycle.State.CREATED
        }

        override fun onStart(owner: LifecycleOwner) {
            registry.currentState = Lifecycle.State.STARTED
        }

        override fun onResume(owner: LifecycleOwner) {
            registry.currentState = Lifecycle.State.RESUMED
        }

        override fun onPause(owner: LifecycleOwner) {
            registry.currentState = Lifecycle.State.STARTED
        }

        override fun onStop(owner: LifecycleOwner) {
            registry.currentState = Lifecycle.State.CREATED
        }

        override fun onDestroy(owner: LifecycleOwner) {
            dispose()
        }

        fun dispose() {
            parentLifecycle.removeObserver(this)
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
