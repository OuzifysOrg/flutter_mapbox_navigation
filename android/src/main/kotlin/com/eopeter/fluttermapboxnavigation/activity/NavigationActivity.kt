// The replay trip session (route simulation) is still behind Mapbox's
// experimental-preview opt-in in v3, exactly as in their own example app.
@file:OptIn(com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI::class)

package com.eopeter.fluttermapboxnavigation.activity

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.eopeter.fluttermapboxnavigation.FlutterMapboxNavigationPlugin
import com.eopeter.fluttermapboxnavigation.R
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.eopeter.fluttermapboxnavigation.models.MapBoxEvents
import com.eopeter.fluttermapboxnavigation.models.MapBoxRouteProgressEvent
import com.eopeter.fluttermapboxnavigation.models.Waypoint
import com.eopeter.fluttermapboxnavigation.models.WaypointSet
import com.eopeter.fluttermapboxnavigation.utilities.PluginUtilities.Companion.sendEvent
import com.google.gson.Gson
import com.mapbox.api.directions.v5.models.DirectionsRoute
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Expected
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.core.lifecycle.requireMapboxNavigation
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
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
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

/**
 * Full-screen turn-by-turn on Navigation SDK v3.
 *
 * v2 delegated the whole screen to Drop-In UI (`NavigationView`), which does not
 * exist in v3 — this activity now assembles the same experience from v3's
 * modular pieces (route line, navigation camera, maneuver banner, trip
 * progress, voice), modelled directly on Mapbox's own TurnByTurnExperience
 * example. **The Flutter-facing contract is unchanged**: same intent extras,
 * same broadcasts, same event stream (ROUTE_BUILDING/BUILT/…, PROGRESS_CHANGE,
 * NAVIGATION_RUNNING/CANCELLED, ON_ARRIVAL).
 */
class NavigationActivity : AppCompatActivity() {

    private lateinit var binding: NavigationActivityBinding

    private var finishBroadcastReceiver: BroadcastReceiver? = null
    private var addWayPointsBroadcastReceiver: BroadcastReceiver? = null
    private var points: MutableList<Waypoint> = mutableListOf()
    private var isNavigationInProgress = false

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
    private lateinit var replayProgressObserver: ReplayProgressObserver

    private val pixelDensity get() = resources.displayMetrics.density
    private val overviewPadding: EdgeInsets by lazy {
        EdgeInsets(140.0 * pixelDensity, 40.0 * pixelDensity, 120.0 * pixelDensity, 40.0 * pixelDensity)
    }
    private val followingPadding: EdgeInsets by lazy {
        EdgeInsets(180.0 * pixelDensity, 40.0 * pixelDensity, 150.0 * pixelDensity, 40.0 * pixelDensity)
    }

    private var isVoiceInstructionsMuted = !FlutterMapboxNavigationPlugin.voiceInstructionsEnabled
        set(value) {
            field = value
            if (value) {
                binding.soundButton.muteAndExtend(BUTTON_ANIMATION_DURATION)
                voiceInstructionsPlayer.volume(SpeechVolume(0f))
            } else {
                binding.soundButton.unmuteAndExtend(BUTTON_ANIMATION_DURATION)
                voiceInstructionsPlayer.volume(SpeechVolume(1f))
            }
        }

    // ---- Observers -----------------------------------------------------------

    private val locationObserver = object : LocationObserver {
        var firstLocationUpdateReceived = false

        override fun onNewRawLocation(rawLocation: Location) {}

        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            navigationLocationProvider.changePosition(
                location = enhancedLocation,
                keyPoints = locationMatcherResult.keyPoints,
            )
            viewportDataSource.onLocationChanged(enhancedLocation)
            viewportDataSource.evaluate()
            if (!firstLocationUpdateReceived) {
                firstLocationUpdateReceived = true
                navigationCamera.requestNavigationCameraToOverview(
                    stateTransitionOptions = NavigationCameraTransitionOptions.Builder()
                        .maxDuration(0)
                        .build()
                )
            }
        }
    }

    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        viewportDataSource.onRouteProgressChanged(routeProgress)
        viewportDataSource.evaluate()

        binding.mapView.mapboxMap.style?.let { style ->
            routeArrowView.renderManeuverUpdate(
                style,
                routeArrowApi.addUpcomingManeuverArrow(routeProgress)
            )
        }

        val maneuvers = maneuverApi.getManeuvers(routeProgress)
        maneuvers.fold(
            { /* no maneuver data for this tick — keep the last banner */ },
            {
                binding.maneuverView.visibility = View.VISIBLE
                binding.maneuverView.renderManeuvers(maneuvers)
            }
        )

        binding.tripProgressView.render(tripProgressApi.getTripProgress(routeProgress))

        // Flutter side: progress + the banner text (kept from v2 behaviour).
        FlutterMapboxNavigationPlugin.distanceRemaining = routeProgress.distanceRemaining
        FlutterMapboxNavigationPlugin.durationRemaining = routeProgress.durationRemaining
        sendEvent(MapBoxRouteProgressEvent(routeProgress))
        routeProgress.bannerInstructions?.primary()?.text()?.let {
            sendEvent(MapBoxEvents.BANNER_INSTRUCTION, it)
        }
    }

    private val routesObserver = RoutesObserver { routeUpdateResult ->
        if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
            routeLineApi.setNavigationRoutes(routeUpdateResult.navigationRoutes) { value ->
                binding.mapView.mapboxMap.style?.apply {
                    routeLineView.renderRouteDrawData(this, value)
                }
            }
            viewportDataSource.onRouteChanged(routeUpdateResult.navigationRoutes.first())
            viewportDataSource.evaluate()
            sendEvent(MapBoxEvents.REROUTE_ALONG)
        } else {
            binding.mapView.mapboxMap.style?.let { style ->
                routeLineApi.clearRouteLine { value ->
                    routeLineView.renderClearRouteLineValue(style, value)
                }
                routeArrowView.render(style, routeArrowApi.clearArrows())
            }
            viewportDataSource.clearRouteData()
            viewportDataSource.evaluate()
        }
    }

    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        sendEvent(MapBoxEvents.SPEECH_ANNOUNCEMENT, voiceInstructions.announcement().toString())
        speechApi.generate(voiceInstructions, speechCallback)
    }

    private val speechCallback =
        MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
            expected.fold(
                { error -> voiceInstructionsPlayer.play(error.fallback, voiceInstructionsPlayerCallback) },
                { value -> voiceInstructionsPlayer.play(value.announcement, voiceInstructionsPlayerCallback) }
            )
        }

    private val voiceInstructionsPlayerCallback =
        MapboxNavigationConsumer<SpeechAnnouncement> { value -> speechApi.clean(value) }

    private val offRouteObserver = OffRouteObserver { offRoute ->
        if (offRoute) sendEvent(MapBoxEvents.USER_OFF_ROUTE)
    }

    private val arrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            isNavigationInProgress = false
            sendEvent(MapBoxEvents.ON_ARRIVAL)
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}
        override fun onWaypointArrival(routeProgress: RouteProgress) {}
    }

    private val mapboxNavigation: MapboxNavigation by requireMapboxNavigation(
        onResumedObserver = object : MapboxNavigationObserver {
            @SuppressLint("MissingPermission")
            override fun onAttached(mapboxNavigation: MapboxNavigation) {
                mapboxNavigation.registerRoutesObserver(routesObserver)
                mapboxNavigation.registerLocationObserver(locationObserver)
                mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
                mapboxNavigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
                mapboxNavigation.registerOffRouteObserver(offRouteObserver)
                mapboxNavigation.registerArrivalObserver(arrivalObserver)

                if (FlutterMapboxNavigationPlugin.simulateRoute) {
                    replayProgressObserver = ReplayProgressObserver(mapboxNavigation.mapboxReplayer)
                    mapboxNavigation.registerRouteProgressObserver(replayProgressObserver)
                    mapboxNavigation.startReplayTripSession()
                } else {
                    mapboxNavigation.startTripSession()
                }
            }

            override fun onDetached(mapboxNavigation: MapboxNavigation) {
                mapboxNavigation.unregisterRoutesObserver(routesObserver)
                mapboxNavigation.unregisterLocationObserver(locationObserver)
                mapboxNavigation.unregisterRouteProgressObserver(routeProgressObserver)
                mapboxNavigation.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
                mapboxNavigation.unregisterOffRouteObserver(offRouteObserver)
                mapboxNavigation.unregisterArrivalObserver(arrivalObserver)
                if (FlutterMapboxNavigationPlugin.simulateRoute) {
                    mapboxNavigation.unregisterRouteProgressObserver(replayProgressObserver)
                    mapboxNavigation.mapboxReplayer.finish()
                }
            }
        },
        onInitialize = this::initNavigation
    )

    // ---- Lifecycle -----------------------------------------------------------

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.Theme_AppCompat_NoActionBar)
        binding = NavigationActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewportDataSource = MapboxNavigationViewportDataSource(binding.mapView.mapboxMap)
        navigationCamera = NavigationCamera(
            binding.mapView.mapboxMap,
            binding.mapView.camera,
            viewportDataSource
        )
        binding.mapView.camera.addCameraAnimationsLifecycleListener(
            NavigationBasicGesturesHandler(navigationCamera)
        )
        navigationCamera.registerNavigationCameraStateChangeObserver { state ->
            when (state) {
                NavigationCameraState.TRANSITION_TO_FOLLOWING,
                NavigationCameraState.FOLLOWING -> binding.recenter.visibility = View.INVISIBLE
                NavigationCameraState.TRANSITION_TO_OVERVIEW,
                NavigationCameraState.OVERVIEW,
                NavigationCameraState.IDLE -> binding.recenter.visibility = View.VISIBLE
            }
        }
        viewportDataSource.overviewPadding = overviewPadding
        viewportDataSource.followingPadding = followingPadding

        val distanceFormatterOptions = DistanceFormatterOptions.Builder(this).build()
        maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(distanceFormatterOptions))
        tripProgressApi = MapboxTripProgressApi(
            TripProgressUpdateFormatter.Builder(this)
                .distanceRemainingFormatter(DistanceRemainingFormatter(distanceFormatterOptions))
                .timeRemainingFormatter(TimeRemainingFormatter(this))
                .percentRouteTraveledFormatter(PercentDistanceTraveledFormatter())
                .estimatedTimeToArrivalFormatter(
                    EstimatedTimeToArrivalFormatter(this, TimeFormat.NONE_SPECIFIED)
                )
                .build()
        )

        val language = FlutterMapboxNavigationPlugin.navigationLanguage
        speechApi = MapboxSpeechApi(this, language)
        voiceInstructionsPlayer = MapboxVoiceInstructionsPlayer(this, language)

        routeLineApi = MapboxRouteLineApi(MapboxRouteLineApiOptions.Builder().build())
        routeLineView = MapboxRouteLineView(
            MapboxRouteLineViewOptions.Builder(this)
                .routeLineBelowLayerId("road-label-navigation")
                .build()
        )
        routeArrowView = MapboxRouteArrowView(RouteArrowOptions.Builder(this).build())

        // Day/night map style: honour the caller's URLs, fall back to the v3
        // navigation styles (v2's customizeViewOptions is gone with Drop-In).
        val nightMode = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val styleUrl = if (nightMode) {
            FlutterMapboxNavigationPlugin.mapStyleUrlNight ?: NavigationStyles.NAVIGATION_NIGHT_STYLE
        } else {
            FlutterMapboxNavigationPlugin.mapStyleUrlDay ?: NavigationStyles.NAVIGATION_DAY_STYLE
        }
        binding.mapView.mapboxMap.loadStyle(styleUrl) { style ->
            routeLineView.initializeLayers(style)
            if (FlutterMapboxNavigationPlugin.longPressDestinationEnabled) {
                binding.mapView.gestures.addOnMapLongClickListener { point ->
                    navigationLocationProvider.lastLocation?.let { last ->
                        val set = WaypointSet()
                        set.add(Waypoint(Point.fromLngLat(last.longitude, last.latitude)))
                        set.add(Waypoint(point))
                        requestRoutes(set)
                    }
                    true
                }
            }
        }

        binding.stop.setOnClickListener {
            cancelNavigation()
            finish()
        }
        binding.recenter.setOnClickListener {
            navigationCamera.requestNavigationCameraToFollowing()
            binding.routeOverview.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }
        binding.routeOverview.setOnClickListener {
            navigationCamera.requestNavigationCameraToOverview()
            binding.recenter.showTextAndExtend(BUTTON_ANIMATION_DURATION)
        }
        binding.soundButton.setOnClickListener {
            isVoiceInstructionsMuted = !isVoiceInstructionsMuted
        }
        if (isVoiceInstructionsMuted) binding.soundButton.mute() else binding.soundButton.unmute()

        // KEPT (Android 14 / API 34+): registerReceiver must declare an export
        // state or the activity crashes with a SecurityException on launch.
        // These are internal app broadcasts → RECEIVER_NOT_EXPORTED.
        finishBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                cancelNavigation()
                finish()
            }
        }
        addWayPointsBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                val stops = intent.getSerializableExtra("waypoints") as? MutableList<Waypoint>
                if (stops != null) {
                    val set = WaypointSet()
                    points.addAll(stops)
                    points.map { set.add(it) }
                    requestRoutes(set)
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            finishBroadcastReceiver,
            IntentFilter(NavigationLauncher.KEY_STOP_NAVIGATION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            addWayPointsBroadcastReceiver,
            IntentFilter(NavigationLauncher.KEY_ADD_WAYPOINTS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Touching `mapboxNavigation` initializes MapboxNavigationApp and starts
        // the trip session (see requireMapboxNavigation above).
        mapboxNavigation

        if (FlutterMapboxNavigationPlugin.enableFreeDriveMode) {
            // Free drive = trip session with no routes; the map follows the puck.
            sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
            return
        }

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val p = intent.getSerializableExtra("waypoints") as? MutableList<Waypoint>
        if (p != null) points = p
        val waypointSet = WaypointSet()
        points.map { waypointSet.add(it) }
        if (!waypointSet.isEmpty) requestRoutes(waypointSet)
    }

    private fun initNavigation() {
        MapboxNavigationApp.setup(NavigationOptions.Builder(this).build())

        binding.mapView.location.apply {
            setLocationProvider(navigationLocationProvider)
            locationPuck = LocationPuck2D(
                bearingImage = ImageHolder.from(
                    com.mapbox.navigation.ui.components.R.drawable.mapbox_navigation_puck_icon
                )
            )
            puckBearingEnabled = true
            enabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishBroadcastReceiver?.let { unregisterReceiver(it) }
        addWayPointsBroadcastReceiver?.let { unregisterReceiver(it) }
        maneuverApi.cancel()
        routeLineApi.cancel()
        routeLineView.cancel()
        speechApi.cancel()
        voiceInstructionsPlayer.shutdown()
    }

    fun tryCancelNavigation() {
        if (isNavigationInProgress) {
            isNavigationInProgress = false
            sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
        }
    }

    private fun cancelNavigation() {
        mapboxNavigation.setNavigationRoutes(listOf())
        if (FlutterMapboxNavigationPlugin.simulateRoute) {
            mapboxNavigation.mapboxReplayer.stop()
            mapboxNavigation.mapboxReplayer.clearEvents()
        }
        tryCancelNavigation()
    }

    // ---- Routing -------------------------------------------------------------

    private fun requestRoutes(waypointSet: WaypointSet) {
        sendEvent(MapBoxEvents.ROUTE_BUILDING)
        mapboxNavigation.requestRoutes(
            RouteOptions.builder()
                .applyDefaultNavigationOptions()
                .applyLanguageAndVoiceUnitOptions(this)
                .coordinatesList(waypointSet.coordinatesList())
                .waypointIndicesList(waypointSet.waypointsIndices())
                .waypointNamesList(waypointSet.waypointsNames())
                .language(FlutterMapboxNavigationPlugin.navigationLanguage)
                .alternatives(FlutterMapboxNavigationPlugin.showAlternateRoutes)
                .voiceUnits(FlutterMapboxNavigationPlugin.navigationVoiceUnits)
                .bannerInstructions(FlutterMapboxNavigationPlugin.bannerInstructionsEnabled)
                .voiceInstructions(FlutterMapboxNavigationPlugin.voiceInstructionsEnabled)
                .steps(true)
                .build(),
            object : NavigationRouterCallback {
                override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {
                    sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                }

                override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
                    sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                }

                override fun onRoutesReady(
                    routes: List<NavigationRoute>,
                    routerOrigin: String
                ) {
                    if (routes.isEmpty()) {
                        sendEvent(MapBoxEvents.ROUTE_BUILD_NO_ROUTES_FOUND)
                        return
                    }
                    sendEvent(
                        MapBoxEvents.ROUTE_BUILT,
                        Gson().toJson(routes.map { it.directionsRoute.toJson() })
                    )
                    setRouteAndStartNavigation(routes)
                }
            }
        )
    }

    private fun setRouteAndStartNavigation(routes: List<NavigationRoute>) {
        mapboxNavigation.setNavigationRoutes(routes)
        isNavigationInProgress = true

        binding.soundButton.visibility = View.VISIBLE
        binding.routeOverview.visibility = View.VISIBLE
        binding.tripProgressCard.visibility = View.VISIBLE

        navigationCamera.requestNavigationCameraToOverview()

        if (FlutterMapboxNavigationPlugin.simulateRoute) {
            startSimulation(routes.first().directionsRoute)
        }
        sendEvent(MapBoxEvents.NAVIGATION_RUNNING)
    }

    private fun startSimulation(route: DirectionsRoute) {
        with(mapboxNavigation.mapboxReplayer) {
            stop()
            clearEvents()
            val replayData = replayRouteMapper.mapDirectionsRouteGeometry(route)
            pushEvents(replayData)
            seekTo(replayData[0])
            play()
        }
    }

    private companion object {
        const val BUTTON_ANIMATION_DURATION = 1500L
    }
}
