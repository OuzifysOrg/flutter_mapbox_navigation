// The replay trip session (route simulation) is still behind Mapbox's
// experimental-preview opt-in in v3, exactly as in their own example app.
@file:OptIn(com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI::class)

package com.eopeter.fluttermapboxnavigation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.lifecycle.LifecycleOwner
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.MapBoxRouteProgressEvent
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities
import com.google.gson.Gson
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Expected
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.replay.route.ReplayProgressObserver
import com.mapbox.navigation.core.replay.route.ReplayRouteMapper
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.OffRouteObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.progress.api.MapboxTripProgressApi
import com.mapbox.navigation.tripdata.progress.model.DistanceRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.tripdata.progress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.tripdata.progress.model.TimeRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.voice.model.SpeechAnnouncement
import com.mapbox.navigation.voice.model.SpeechError
import com.mapbox.navigation.voice.model.SpeechValue
import com.mapbox.navigation.voice.model.SpeechVolume
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * The embedded-view navigation controller, rebuilt on Navigation SDK v3.
 *
 * v2 handed the embedded platform view to Drop-In UI; v3 has no Drop-In, so
 * this class owns the same modular pieces the full-screen activity uses (route
 * line, camera, maneuvers, trip progress, voice) inside the host's MapView.
 * The Flutter method/event channel contract is unchanged.
 */
open class TurnByTurn(
    ctx: Context,
    act: Activity,
    bind: NavigationActivityBinding,
    accessToken: String
) : MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler,
    Application.ActivityLifecycleCallbacks {

    open fun initFlutterChannelHandlers() {
        this.methodChannel?.setMethodCallHandler(this)
        this.eventChannel?.setStreamHandler(this)
    }

    open fun initNavigation() {
        if (!MapboxNavigationApp.isSetup()) {
            MapboxNavigationApp.setup(NavigationOptions.Builder(this.context).build())
        }
        MapboxNavigationApp.attach(this.activity as LifecycleOwner)

        // Camera + viewport
        this.viewportDataSource =
            MapboxNavigationViewportDataSource(this.binding.mapView.mapboxMap)
        this.navigationCamera = NavigationCamera(
            this.binding.mapView.mapboxMap,
            this.binding.mapView.camera,
            this.viewportDataSource
        )
        this.binding.mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(this.navigationCamera)
        )
        this.navigationCamera.registerNavigationCameraStateChangeObserver { state ->
            when (state) {
                NavigationCameraState.TRANSITION_TO_FOLLOWING,
                NavigationCameraState.FOLLOWING ->
                    this.binding.recenter.visibility = View.INVISIBLE
                NavigationCameraState.TRANSITION_TO_OVERVIEW,
                NavigationCameraState.OVERVIEW,
                NavigationCameraState.IDLE ->
                    this.binding.recenter.visibility = View.VISIBLE
            }
        }
        val density = this.context.resources.displayMetrics.density
        this.viewportDataSource.overviewPadding =
            EdgeInsets(140.0 * density, 40.0 * density, 120.0 * density, 40.0 * density)
        this.viewportDataSource.followingPadding =
            EdgeInsets(180.0 * density, 40.0 * density, 150.0 * density, 40.0 * density)

        // Trip data + views
        val distanceFormatterOptions = DistanceFormatterOptions.Builder(this.context).build()
        this.maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(distanceFormatterOptions))
        this.tripProgressApi = MapboxTripProgressApi(
            TripProgressUpdateFormatter.Builder(this.context)
                .distanceRemainingFormatter(DistanceRemainingFormatter(distanceFormatterOptions))
                .timeRemainingFormatter(TimeRemainingFormatter(this.context))
                .percentRouteTraveledFormatter(PercentDistanceTraveledFormatter())
                .estimatedTimeToArrivalFormatter(
                    EstimatedTimeToArrivalFormatter(this.context, TimeFormat.NONE_SPECIFIED)
                )
                .build()
        )
        this.speechApi = MapboxSpeechApi(this.context, this.navigationLanguage)
        this.voiceInstructionsPlayer =
            MapboxVoiceInstructionsPlayer(this.context, this.navigationLanguage)
        this.routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
        this.routeLineView = MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(this.context)
                .routeLineBelowLayerId("road-label-navigation")
                .build()
        )
        this.routeArrowView =
            MapboxRouteArrowView(RouteArrowOptions.Builder(this.context).build())

        // Puck
        this.binding.mapView.location.apply {
            setLocationProvider(this@TurnByTurn.navigationLocationProvider)
            locationPuck = LocationPuck2D(
                bearingImage = ImageHolder.from(
                    com.mapbox.navigation.ui.components.R.drawable.mapbox_navigation_puck_icon
                )
            )
            puckBearingEnabled = true
            enabled = true
        }

        // Style — day/night from the host's configuration, like the activity.
        val nightMode = this.context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val styleUrl = if (nightMode) {
            this.mapStyleUrlNight ?: NavigationStyles.NAVIGATION_NIGHT_STYLE
        } else {
            this.mapStyleUrlDay ?: NavigationStyles.NAVIGATION_DAY_STYLE
        }
        this.binding.mapView.mapboxMap.loadStyle(styleUrl) { style ->
            this.routeLineView.initializeLayers(style)
        }

        // Route-preview taps: promote a tapped alternative to the primary
        // route. Preview only — never during guidance (Harry, 2026-08-02;
        // standard Mapbox behaviour). Returns false so camera gestures are
        // unaffected.
        this.binding.mapView.mapboxMap.addOnMapClickListener { point ->
            this.onMapTapForRouteSelection(point)
            false
        }

        this.binding.recenter.setOnClickListener {
            this.navigationCamera.requestNavigationCameraToFollowing()
        }
        this.binding.routeOverview.setOnClickListener {
            this.navigationCamera.requestNavigationCameraToOverview()
        }
        this.binding.soundButton.setOnClickListener {
            this.isVoiceInstructionsMuted = !this.isVoiceInstructionsMuted
        }
        this.binding.stop.setOnClickListener {
            this.finishNavigation()
        }

        this.registerObservers()
        MapboxNavigationApp.current()?.startTripSession(withForegroundService = false)
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "enableOfflineRouting" -> {
                // not implemented on Android
            }
            // Parity with the iOS embedded view: same channel, same {muted}
            // argument, same applied-state reply. The setter drives the same
            // field as the native sound button, so the two stay one truth.
            "getVoiceMuted" -> {
                result.success(this.isVoiceInstructionsMuted)
            }
            "setVoiceMuted" -> {
                val muted = (methodCall.arguments as? Map<*, *>)?.get("muted") as? Boolean
                if (muted != null) {
                    this.isVoiceInstructionsMuted = muted
                }
                result.success(this.isVoiceInstructionsMuted)
            }
            "buildRoute" -> {
                this.buildRoute(methodCall, result)
            }
            "clearRoute" -> {
                this.clearRoute(methodCall, result)
            }
            "startFreeDrive" -> {
                FlutterMapboxNavigationPlugin.enableFreeDriveMode = true
                this.startFreeDrive()
            }
            "startNavigation" -> {
                FlutterMapboxNavigationPlugin.enableFreeDriveMode = false
                this.startNavigation(methodCall, result)
            }
            "selectRoute" -> {
                // Programmatic twin of the preview map tap — used by the
                // Android Auto route preview screen. Index into the order the
                // last ROUTE_BUILT reported.
                val index = (methodCall.arguments as? Map<*, *>)?.get("index") as? Int
                val routes = this.currentRoutes
                if (index == null || routes == null || index !in routes.indices) {
                    result.success(false)
                } else {
                    this.selectRoute(routes[index])
                    result.success(true)
                }
            }
            "finishNavigation" -> {
                this.finishNavigation(methodCall, result)
            }
            "getDistanceRemaining" -> {
                result.success(this.distanceRemaining)
            }
            "getDurationRemaining" -> {
                result.success(this.durationRemaining)
            }
            else -> result.notImplemented()
        }
    }

    private fun buildRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        this.isNavigationCanceled = false

        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) this.setOptions(arguments)
        this.addedWaypoints.clear()
        val points = arguments?.get("wayPoints") as HashMap<*, *>
        for (item in points) {
            val point = item.value as HashMap<*, *>
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            this.addedWaypoints.add(Waypoint(Point.fromLngLat(longitude, latitude)))
        }
        this.getRoute(this.context)
        result.success(true)
    }

    private fun getRoute(context: Context) {
        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILDING)
        MapboxNavigationApp.current()!!.requestRoutes(
            routeOptions = RouteOptions
                .builder()
                .applyDefaultNavigationOptions(navigationMode)
                .applyLanguageAndVoiceUnitOptions(context)
                .coordinatesList(this.addedWaypoints.coordinatesList())
                .waypointIndicesList(this.addedWaypoints.waypointsIndices())
                .waypointNamesList(this.addedWaypoints.waypointsNames())
                .language(navigationLanguage)
                .alternatives(alternatives)
                .steps(true)
                .voiceUnits(navigationVoiceUnits)
                .bannerInstructions(bannerInstructionsEnabled)
                .voiceInstructions(voiceInstructionsEnabled)
                .build(),
            callback = object : NavigationRouterCallback {
                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: String
                ) {
                    // Hand the routes to the SDK's preview state. The
                    // registered RoutesPreviewObserver is the single sync
                    // point: it renders the lines, updates currentRoutes and
                    // sends ROUTE_BUILT — for this build, for map taps, and
                    // for the car's selectRoute alike. It also puts the
                    // preview where the Android Auto surface can draw it.
                    MapboxNavigationApp.current()?.setRoutesPreview(routes)
                    this@TurnByTurn.navigationCamera.requestNavigationCameraToOverview()
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions
                ) {
                    PluginUtilities.sendEvent(
                        MapBoxEvents.ROUTE_BUILD_FAILED,
                        reasons.joinToString(separator = " | ")
                    )
                }

                override fun onCanceled(
                    routeOptions: RouteOptions,
                    routerOrigin: String
                ) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }
            }
        )
    }

    // Hit-test a preview tap against the drawn route lines and, when it lands
    // on an alternative, make that the primary route. findClosestRoute is
    // the SDK's own hit-test (verified in ui-maps 3.27.0); 30dp keeps parity
    // with iOS's default tapGestureDistanceThreshold.
    private fun onMapTapForRouteSelection(point: Point) {
        if (this.isNavigationRunning) return
        val routes = this.currentRoutes ?: return
        if (routes.size < 2) return

        val threshold = 30f * this.context.resources.displayMetrics.density
        this.routeLineApi.findClosestRoute(
            point,
            this.binding.mapView.mapboxMap,
            threshold
        ) { expected ->
            expected.value?.navigationRoute?.let { tapped -> this.selectRoute(tapped) }
        }
    }

    // Promote a route to primary in the SDK's preview state. The preview
    // observer does the rendering and eventing, so a phone tap, the car's
    // selectRoute and the initial build all flow through one path.
    private fun selectRoute(route: NavigationRoute) {
        if (this.isNavigationRunning) return
        if (this.currentRoutes?.firstOrNull()?.id == route.id) return
        try {
            MapboxNavigationApp.current()?.changeRoutesPreviewPrimaryRoute(route)
        } catch (e: IllegalArgumentException) {
            // The route is no longer in the preview (stale tap) — ignore.
        }
    }

    // The single sync point for route preview: initial build, phone map taps
    // and the car's selectRoute all land here via the session's preview state.
    private val routesPreviewObserver =
        com.mapbox.navigation.core.preview.RoutesPreviewObserver { update ->
            val preview = update.routesPreview ?: return@RoutesPreviewObserver
            val routes = preview.routesList
            if (routes.isEmpty()) return@RoutesPreviewObserver
            // Primary first — the order startNavigation will use.
            val primary = preview.primaryRoute
            val ordered = listOf(primary) + routes.filter { it.id != primary.id }
            this.currentRoutes = ordered
            PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILT, routeSummariesJson(ordered))
            this.routeLineApi.setNavigationRoutes(ordered) { value ->
                this.binding.mapView.mapboxMap.style?.apply {
                    this@TurnByTurn.routeLineView.renderRouteDrawData(this, value)
                }
            }
            this.viewportDataSource.onRouteChanged(ordered.first())
            this.viewportDataSource.evaluate()
        }

    // Compact per-route summaries for the Dart side's preview sheet — index,
    // label (first leg's road summary), duration and distance. Replaces the
    // earlier full-DirectionsRoute JSON, which nothing consumed.
    private fun routeSummariesJson(routes: List<NavigationRoute>): String {
        val summaries = routes.mapIndexed { i, r ->
            mapOf(
                "index" to i,
                "label" to (r.directionsRoute.legs()?.firstOrNull()?.summary()
                    ?: "Route ${i + 1}"),
                "durationS" to r.directionsRoute.duration(),
                "distanceM" to r.directionsRoute.distance(),
            )
        }
        return Gson().toJson(summaries)
    }

    private fun clearRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        this.currentRoutes = null
        this.isNavigationRunning = false
        MapboxNavigationApp.current()?.setNavigationRoutes(listOf())
        MapboxNavigationApp.current()?.setRoutesPreview(emptyList())
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    }

    @SuppressLint("MissingPermission")
    private fun startFreeDrive() {
        // Free drive on v3 is simply an active trip session with no routes set.
        MapboxNavigationApp.current()?.startTripSession(withForegroundService = false)
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
    }

    private fun startNavigation(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) {
            this.setOptions(arguments)
        }

        this.startNavigation()

        if (this.currentRoutes != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun finishNavigation(methodCall: MethodCall, result: MethodChannel.Result) {
        this.finishNavigation()

        if (this.currentRoutes != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        val routes = this.currentRoutes
        if (routes == null) {
            PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
            return
        }
        val navigation = MapboxNavigationApp.current() ?: return
        navigation.setNavigationRoutes(routes)
        if (this.simulateRoute) {
            navigation.startReplayTripSession()
            this.startSimulation(routes.first().directionsRoute)
        } else {
            // Guidance must survive a locked phone (the Android Auto cubby
            // case): the idle preview session runs without a foreground
            // service, and Android cuts background location without one. So
            // real guidance restarts the session under the SDK's own
            // NavigationNotificationService — declared with the location type
            // and all three FOREGROUND_SERVICE*/POST_NOTIFICATIONS
            // permissions in the navigation AAR's manifest (verified 3.27.0).
            navigation.stopTripSession()
            navigation.startTripSession(withForegroundService = true)
        }
        this.navigationCamera.requestNavigationCameraToFollowing()
        this.isNavigationRunning = true
        // The layout ships these invisible and only NavigationActivity's
        // setRouteAndStartNavigation ever showed them — embedded guidance had
        // a working but invisible mute button, i.e. no way to silence voice.
        // The trip card too: the progress observer shows it on the first
        // tick, but that tick needs a fix, and the card holds the stop
        // button — waiting on GPS to allow stopping is the wrong order.
        this.binding.soundButton.visibility = View.VISIBLE
        this.binding.routeOverview.visibility = View.VISIBLE
        this.binding.tripProgressCard.visibility = View.VISIBLE
        if (this.isVoiceInstructionsMuted) {
            this.binding.soundButton.mute()
        } else {
            this.binding.soundButton.unmute()
        }
        // Preview is over — clear it AFTER setNavigationRoutes so the empty
        // update is a no-op in the observer and the car surface hands over
        // from the preview lines to the active-guidance line.
        navigation.setRoutesPreview(emptyList())
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
    }

    private fun startSimulation(route: DirectionsRoute) {
        val navigation = MapboxNavigationApp.current() ?: return
        with(navigation.mapboxReplayer) {
            stop()
            clearEvents()
            val replayData = this@TurnByTurn.replayRouteMapper.mapDirectionsRouteGeometry(route)
            pushEvents(replayData)
            seekTo(replayData[0])
            play()
        }
    }

    @SuppressLint("MissingPermission")
    private fun finishNavigation(isOffRouted: Boolean = false) {
        val navigation = MapboxNavigationApp.current() ?: return
        navigation.setNavigationRoutes(listOf())
        if (this.simulateRoute) {
            navigation.mapboxReplayer.stop()
            navigation.mapboxReplayer.clearEvents()
        }
        this.isNavigationCanceled = true
        this.isNavigationRunning = false
        // Drop back to the serviceless idle session so the guidance
        // notification clears the moment guidance ends, not when the view
        // dies. The replay session never started a service to stop.
        if (!this.simulateRoute) {
            navigation.stopTripSession()
            navigation.startTripSession(withForegroundService = false)
        }
        // Guidance chrome off again — the host screen usually pops on
        // NAVIGATION_CANCELLED, but a preview that stays up should not keep
        // dead guidance buttons.
        this.binding.soundButton.visibility = View.INVISIBLE
        this.binding.routeOverview.visibility = View.INVISIBLE
        this.binding.tripProgressCard.visibility = View.INVISIBLE
        this.binding.maneuverView.visibility = View.INVISIBLE
        navigation.setRoutesPreview(emptyList())
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    }

    private fun setOptions(arguments: Map<*, *>) {
        val navMode = arguments["mode"] as? String
        if (navMode != null) {
            when (navMode) {
                "walking" -> this.navigationMode = DirectionsCriteria.PROFILE_WALKING
                "cycling" -> this.navigationMode = DirectionsCriteria.PROFILE_CYCLING
                "driving" -> this.navigationMode = DirectionsCriteria.PROFILE_DRIVING
            }
        }

        val simulated = arguments["simulateRoute"] as? Boolean
        if (simulated != null) {
            this.simulateRoute = simulated
        }

        val language = arguments["language"] as? String
        if (language != null) {
            this.navigationLanguage = language
        }

        val units = arguments["units"] as? String

        if (units != null) {
            if (units == "imperial") {
                this.navigationVoiceUnits = DirectionsCriteria.IMPERIAL
            } else if (units == "metric") {
                this.navigationVoiceUnits = DirectionsCriteria.METRIC
            }
        }

        this.mapStyleUrlDay = arguments["mapStyleUrlDay"] as? String
        this.mapStyleUrlNight = arguments["mapStyleUrlNight"] as? String

        this.initialLatitude = arguments["initialLatitude"] as? Double
        this.initialLongitude = arguments["initialLongitude"] as? Double

        val zm = arguments["zoom"] as? Double
        if (zm != null) {
            this.zoom = zm
        }

        val br = arguments["bearing"] as? Double
        if (br != null) {
            this.bearing = br
        }

        val tt = arguments["tilt"] as? Double
        if (tt != null) {
            this.tilt = tt
        }

        val optim = arguments["isOptimized"] as? Boolean
        if (optim != null) {
            this.isOptimized = optim
        }

        val anim = arguments["animateBuildRoute"] as? Boolean
        if (anim != null) {
            this.animateBuildRoute = anim
        }

        val altRoute = arguments["alternatives"] as? Boolean
        if (altRoute != null) {
            this.alternatives = altRoute
        }

        val voiceEnabled = arguments["voiceInstructionsEnabled"] as? Boolean
        if (voiceEnabled != null) {
            this.voiceInstructionsEnabled = voiceEnabled
            this.isVoiceInstructionsMuted = !voiceEnabled
        }

        val bannerEnabled = arguments["bannerInstructionsEnabled"] as? Boolean
        if (bannerEnabled != null) {
            this.bannerInstructionsEnabled = bannerEnabled
        }

        val longPress = arguments["longPressDestinationEnabled"] as? Boolean
        if (longPress != null) {
            this.longPressDestinationEnabled = longPress
        }
    }

    open fun registerObservers() {
        MapboxNavigationApp.current()?.registerVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.registerOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.registerRoutesObserver(this.routesObserver)
        MapboxNavigationApp.current()?.registerRoutesPreviewObserver(this.routesPreviewObserver)
        MapboxNavigationApp.current()?.registerLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.registerRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.registerArrivalObserver(this.arrivalObserver)
    }

    open fun unregisterObservers() {
        MapboxNavigationApp.current()?.unregisterVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.unregisterOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.unregisterRoutesObserver(this.routesObserver)
        MapboxNavigationApp.current()?.unregisterRoutesPreviewObserver(this.routesPreviewObserver)
        MapboxNavigationApp.current()?.unregisterLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.unregisterRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.unregisterArrivalObserver(this.arrivalObserver)
        this.maneuverApi.cancel()
        this.routeLineApi.cancel()
        this.routeLineView.cancel()
        this.speechApi.cancel()
        this.voiceInstructionsPlayer.shutdown()
    }

    // Flutter stream listener delegate methods
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        FlutterMapboxNavigationPlugin.eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        FlutterMapboxNavigationPlugin.eventSink = null
    }

    private val context: Context = ctx
    val activity: Activity = act
    private val token: String = accessToken
    open var methodChannel: MethodChannel? = null
    open var eventChannel: EventChannel? = null
    private var lastLocation: Location? = null

    /**
     * Helper class that keeps added waypoints and transforms them to the [RouteOptions] params.
     */
    private val addedWaypoints = WaypointSet()

    // Config
    private var initialLatitude: Double? = null
    private var initialLongitude: Double? = null

    private var navigationMode = DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
    var simulateRoute = false
    private var mapStyleUrlDay: String? = null
    private var mapStyleUrlNight: String? = null
    private var navigationLanguage = "en"
    private var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
    private var zoom = 15.0
    private var bearing = 0.0
    private var tilt = 0.0
    private var distanceRemaining: Float? = null
    private var durationRemaining: Double? = null

    private var alternatives = true

    var allowsUTurnAtWayPoints = false
    var enableRefresh = false
    private var voiceInstructionsEnabled = true
    private var bannerInstructionsEnabled = true
    private var longPressDestinationEnabled = true
    private var animateBuildRoute = true
    private var isOptimized = false

    private var currentRoutes: List<NavigationRoute>? = null

    // True between startNavigation and finish/clear — the preview-only guard
    // for tap-to-select. Distinct from isNavigationCanceled, which latches.
    private var isNavigationRunning = false
    private var isNavigationCanceled = false

    /**
     * Bindings to the embedded navigation layout (MapView + component views).
     */
    open val binding: NavigationActivityBinding = bind

    // v3 components
    private lateinit var viewportDataSource: MapboxNavigationViewportDataSource
    private lateinit var navigationCamera: NavigationCamera
    private lateinit var maneuverApi: MapboxManeuverApi
    private lateinit var tripProgressApi: MapboxTripProgressApi
    private lateinit var routeLineApi: MapboxRouteLineApi
    private lateinit var routeLineView: MapboxRouteLineView
    private val routeArrowApi: MapboxRouteArrowApi = MapboxRouteArrowApi()
    private lateinit var routeArrowView: MapboxRouteArrowView
    private lateinit var speechApi: MapboxSpeechApi
    private lateinit var voiceInstructionsPlayer: MapboxVoiceInstructionsPlayer
    private val navigationLocationProvider = NavigationLocationProvider()
    private val replayRouteMapper = ReplayRouteMapper()

    private var isVoiceInstructionsMuted = false
        set(value) {
            field = value
            if (!this::voiceInstructionsPlayer.isInitialized) return
            if (value) {
                this.binding.soundButton.muteAndExtend(1500L)
                this.voiceInstructionsPlayer.volume(SpeechVolume(0f))
                // ⚠️ Muted must mean NO audio focus, not zero-volume playback.
                // A queued announcement played at volume 0 still ducks the
                // driver's music — "random quiet points" (Harry, head unit,
                // 2026-08-07). clear() drops anything in flight; the
                // speechCallback below stops anything new reaching play().
                this.voiceInstructionsPlayer.clear()
            } else {
                this.binding.soundButton.unmuteAndExtend(1500L)
                this.voiceInstructionsPlayer.volume(SpeechVolume(1f))
            }
        }

    private val speechCallback =
        MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
            expected.fold(
                { error ->
                    // Generation is gated on mute, but a request already in
                    // flight when the driver muted still lands here — clean
                    // the audio file instead of playing it and ducking music.
                    if (this.isVoiceInstructionsMuted) {
                        this.speechApi.clean(error.fallback)
                    } else {
                        this.voiceInstructionsPlayer.play(error.fallback, this.voiceCleanupCallback)
                    }
                },
                { value ->
                    if (this.isVoiceInstructionsMuted) {
                        this.speechApi.clean(value.announcement)
                    } else {
                        this.voiceInstructionsPlayer.play(value.announcement, this.voiceCleanupCallback)
                    }
                }
            )
        }

    private val voiceCleanupCallback =
        MapboxNavigationConsumer<SpeechAnnouncement> { value -> this.speechApi.clean(value) }

    /**
     * Gets notified with location updates.
     */
    private val locationObserver = object : LocationObserver {
        var firstLocationUpdateReceived = false

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            this@TurnByTurn.lastLocation = enhancedLocation
            this@TurnByTurn.navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )
            this@TurnByTurn.viewportDataSource.onLocationChanged(enhancedLocation)
            this@TurnByTurn.viewportDataSource.evaluate()
            if (!firstLocationUpdateReceived) {
                firstLocationUpdateReceived = true
                this@TurnByTurn.navigationCamera.requestNavigationCameraToFollowing()
            }
        }

        override fun onNewRawLocation(rawLocation: Location) {
            // no impl
        }
    }

    private val voiceInstructionObserver = VoiceInstructionsObserver { voiceInstructions ->
        PluginUtilities.sendEvent(
            MapBoxEvents.SPEECH_ANNOUNCEMENT,
            voiceInstructions.announcement().toString()
        )
        if (!this.isVoiceInstructionsMuted) {
            this.speechApi.generate(voiceInstructions, this.speechCallback)
        }
    }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        if (offRoute) {
            PluginUtilities.sendEvent(MapBoxEvents.USER_OFF_ROUTE)
        }
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            this.routeLineApi.setNavigationRoutes(routeUpdateResult.navigationRoutes) { value ->
                this.binding.mapView.mapboxMap.style?.apply {
                    this@TurnByTurn.routeLineView.renderRouteDrawData(this, value)
                }
            }
            this.viewportDataSource.onRouteChanged(routeUpdateResult.navigationRoutes.first())
            this.viewportDataSource.evaluate()
            PluginUtilities.sendEvent(MapBoxEvents.REROUTE_ALONG)
        } else {
            this.binding.mapView.mapboxMap.style?.let { style ->
                this.routeLineApi.clearRouteLine { value ->
                    this@TurnByTurn.routeLineView.renderClearRouteLineValue(style, value)
                }
                this.routeArrowView.render(style, this.routeArrowApi.clearArrows())
            }
            this.viewportDataSource.clearRouteData()
            this.viewportDataSource.evaluate()
        }
    }

    /**
     * Gets notified with progress along the currently active route.
     */
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        if (!this.isNavigationCanceled) {
            try {
                this.distanceRemaining = routeProgress.distanceRemaining
                this.durationRemaining = routeProgress.durationRemaining

                this.viewportDataSource.onRouteProgressChanged(routeProgress)
                this.viewportDataSource.evaluate()

                this.binding.mapView.mapboxMap.style?.let { style ->
                    this.routeArrowView.renderManeuverUpdate(
                        style,
                        this.routeArrowApi.addUpcomingManeuverArrow(routeProgress)
                    )
                }

                val maneuvers = this.maneuverApi.getManeuvers(routeProgress)
                maneuvers.fold(
                    { /* keep the previous banner */ },
                    {
                        this.binding.maneuverView.visibility = View.VISIBLE
                        this.binding.maneuverView.renderManeuvers(maneuvers)
                    }
                )
                this.binding.tripProgressCard.visibility = View.VISIBLE
                this.binding.tripProgressView.render(
                    this.tripProgressApi.getTripProgress(routeProgress)
                )

                routeProgress.bannerInstructions?.primary()?.text()?.let {
                    PluginUtilities.sendEvent(MapBoxEvents.BANNER_INSTRUCTION, it)
                }
                val progressEvent = MapBoxRouteProgressEvent(routeProgress)
                PluginUtilities.sendEvent(progressEvent)
            } catch (_: java.lang.Exception) {
                // A malformed progress tick must not take the stream down.
            }
        }
    }

    private val arrivalObserver: ArrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            PluginUtilities.sendEvent(MapBoxEvents.ON_ARRIVAL)
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {
            // not impl
        }

        override fun onWaypointArrival(routeProgress: RouteProgress) {
            // not impl
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
