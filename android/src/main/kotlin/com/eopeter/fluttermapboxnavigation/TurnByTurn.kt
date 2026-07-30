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
        MapboxNavigationApp
            .setup(NavigationOptions.Builder(this.context).build())
            .attach(this.activity as LifecycleOwner)

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
                    this@TurnByTurn.currentRoutes = routes
                    PluginUtilities.sendEvent(
                        MapBoxEvents.ROUTE_BUILT,
                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
                    )
                    // Show the line on the embedded map right away (preview);
                    // guidance starts when startNavigation is called.
                    this@TurnByTurn.routeLineApi.setNavigationRoutes(routes) { value ->
                        this@TurnByTurn.binding.mapView.mapboxMap.style?.apply {
                            this@TurnByTurn.routeLineView.renderRouteDrawData(this, value)
                        }
                    }
                    this@TurnByTurn.viewportDataSource.onRouteChanged(routes.first())
                    this@TurnByTurn.viewportDataSource.evaluate()
                    this@TurnByTurn.navigationCamera.requestNavigationCameraToOverview()
                }

                override fun onFailure(
                    reasons: List<RouterFailure>,
                    routeOptions: RouteOptions
                ) {
                    PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
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

    private fun clearRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        this.currentRoutes = null
        MapboxNavigationApp.current()?.setNavigationRoutes(listOf())
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
            navigation.startTripSession(withForegroundService = false)
        }
        this.navigationCamera.requestNavigationCameraToFollowing()
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

    private fun finishNavigation(isOffRouted: Boolean = false) {
        val navigation = MapboxNavigationApp.current() ?: return
        navigation.setNavigationRoutes(listOf())
        if (this.simulateRoute) {
            navigation.mapboxReplayer.stop()
            navigation.mapboxReplayer.clearEvents()
        }
        this.isNavigationCanceled = true
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
        MapboxNavigationApp.current()?.registerLocationObserver(this.locationObserver)
        MapboxNavigationApp.current()?.registerRouteProgressObserver(this.routeProgressObserver)
        MapboxNavigationApp.current()?.registerArrivalObserver(this.arrivalObserver)
    }

    open fun unregisterObservers() {
        MapboxNavigationApp.current()?.unregisterVoiceInstructionsObserver(this.voiceInstructionObserver)
        MapboxNavigationApp.current()?.unregisterOffRouteObserver(this.offRouteObserver)
        MapboxNavigationApp.current()?.unregisterRoutesObserver(this.routesObserver)
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
            } else {
                this.binding.soundButton.unmuteAndExtend(1500L)
                this.voiceInstructionsPlayer.volume(SpeechVolume(1f))
            }
        }

    private val speechCallback =
        MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
            expected.fold(
                { error ->
                    this.voiceInstructionsPlayer.play(error.fallback, this.voiceCleanupCallback)
                },
                { value ->
                    this.voiceInstructionsPlayer.play(value.announcement, this.voiceCleanupCallback)
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
