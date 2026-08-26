import Flutter
import UIKit
import Combine
import CoreLocation
import MapboxMaps
import MapboxDirections
@_spi(ExperimentalMapboxAPI) import MapboxNavigationCore
import MapboxNavigationUIKit

/// Flutter may ask for the platform view before its final UIKit frame has been
/// applied. Mapbox's NavigationMapView intentionally starts at 64×64, which is
/// too small for its route-camera padding. Forward every real layout pass so
/// the owner can resize Mapbox before calling `showcase()`.
private final class FlutterNavigationContainerView: UIView {
    var onLayout: ((CGRect) -> Void)?

    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?(bounds)
    }
}

/// The embedded platform view (`FlutterMapboxNavigationView`), rebuilt on
/// Navigation SDK v3.
///
/// v2 built this on `RouteResponse` + `MapboxNavigationService` +
/// `NavigationServiceDelegate`, none of which exist in v3. The v3 shape:
///
///  * idle/preview: a v3 `NavigationMapView` fed by the shared provider's
///    location/route-progress publishers (the publisher init is the SDK's
///    embedding path; it is SPI-marked, which is why Package.swift pins the
///    SDK to an EXACT version — an SPI surface must not drift underneath us);
///  * active guidance: Mapbox Core drives the already-mounted preview map so
///    Flutter can keep compositing it without a black platform-view surface;
///  * progress events flow through Mapbox Core publishers (v2's
///    `NavigationServiceDelegate` route is gone).
///
/// The Flutter method/event channel contract is unchanged.
public class FlutterMapboxNavigationView: NavigationFactory, FlutterPlatformView {
    let frame: CGRect
    let viewId: Int64

    let messenger: FlutterBinaryMessenger
    let channel: FlutterMethodChannel
    let eventChannel: FlutterEventChannel

    var arguments: NSDictionary?

    /// The container the Flutter engine composits; hosts the preview map and,
    /// during guidance, the child NavigationViewController's view.
    private let containerView: FlutterNavigationContainerView
    var navigationMapView: NavigationMapView?

    var navigationRoutes: NavigationRoutes?

    /// A route may finish calculating before Flutter has assigned the native
    /// view its real phone-sized frame. Keep the latest presentation request
    /// until layout has moved Mapbox beyond its 64×64 startup rectangle.
    private var pendingShowcase: (routes: NavigationRoutes, shouldFit: Bool)?

    /// iOS active guidance stays on the already-rendering preview map. Moving
    /// its Metal-backed MapView into NavigationViewController makes Flutter's
    /// platform-view surface black even though route progress and voice keep
    /// running. Subscribe to Core directly instead of reparenting that view.
    private var embeddedGuidanceSubscriptions = Set<AnyCancellable>()
    private var embeddedGuidanceActive = false
    private var embeddedArrivalSent = false

    var _mapInitialized = false
    var locationManager = CLLocationManager()

    init(messenger: FlutterBinaryMessenger, frame: CGRect, viewId: Int64, args: Any?) {
        self.frame = frame
        self.viewId = viewId
        self.arguments = args as! NSDictionary?

        self.messenger = messenger
        self.channel = FlutterMethodChannel(name: "flutter_mapbox_navigation/\(viewId)", binaryMessenger: messenger)
        self.eventChannel = FlutterEventChannel(name: "flutter_mapbox_navigation/\(viewId)/events", binaryMessenger: messenger)
        self.containerView = FlutterNavigationContainerView(frame: frame)

        super.init()

        self.containerView.onLayout = { [weak self] bounds in
            guard let self else { return }
            MainActor.assumeIsolated {
                self.containerDidLayout(bounds)
            }
        }

        self.eventChannel.setStreamHandler(self)

        self.channel.setMethodCallHandler { [weak self] (call, result) in
            guard let strongSelf = self else { return }

            // Method calls arrive on the platform (main) thread; the v3 map and
            // provider surfaces are @MainActor, so assert that isolation once.
            MainActor.assumeIsolated {
            let arguments = call.arguments as? NSDictionary

            if call.method == "getPlatformVersion" {
                result("iOS " + UIDevice.current.systemVersion)
            } else if call.method == "buildRoute" {
                strongSelf.buildRoute(arguments: arguments, flutterResult: result)
            } else if call.method == "clearRoute" {
                strongSelf.clearRoute(arguments: arguments, result: result)
            } else if call.method == "getDistanceRemaining" {
                result(strongSelf._distanceRemaining)
            } else if call.method == "getDurationRemaining" {
                result(strongSelf._durationRemaining)
            } else if call.method == "finishNavigation" {
                strongSelf.endNavigation(result: result)
            } else if call.method == "startFreeDrive" {
                strongSelf.startEmbeddedFreeDrive(arguments: arguments, result: result)
            } else if call.method == "startNavigation" {
                strongSelf.startEmbeddedNavigation(arguments: arguments, result: result)
            } else if call.method == "getVoiceMuted" {
                result(strongSelf.getVoiceMuted())
            } else if call.method == "setVoiceMuted" {
                strongSelf.setVoiceMuted(arguments: arguments, result: result)
            } else if call.method == "selectRoute" {
                strongSelf.selectRoute(arguments: arguments, result: result)
            } else if call.method == "reCenter" {
                strongSelf.navigationMapView?.update(navigationCameraState: .following)
                result(true)
            } else if call.method == "routeOverview" {
                // Parity with Android's native routeOverview button: fit the
                // whole remaining route, camera stops following until reCenter.
                strongSelf.navigationMapView?.update(navigationCameraState: .overview)
                result(true)
            } else {
                result("method is not implemented")
            }
            }
        }
    }

    public func view() -> UIView {
        MainActor.assumeIsolated {
            prepareMapForCurrentLayout()
        }
        return containerView
    }

    @MainActor
    private func containerDidLayout(_ bounds: CGRect) {
        guard bounds.width > 64, bounds.height > 64 else { return }
        if !_mapInitialized {
            setupMapView()
        }
        layoutMapView(in: bounds)
        flushPendingShowcaseIfPossible()
    }

    @MainActor
    private func prepareMapForCurrentLayout() {
        let bounds = containerView.bounds
        guard bounds.width > 64, bounds.height > 64 else { return }
        if !_mapInitialized {
            setupMapView()
        }
        layoutMapView(in: bounds)
        flushPendingShowcaseIfPossible()
    }

    @MainActor
    private func layoutMapView(in bounds: CGRect) {
        guard let mapView = navigationMapView else { return }
        mapView.frame = bounds
        mapView.setNeedsLayout()
        mapView.layoutIfNeeded()
        mapView.mapView.setNeedsLayout()
        mapView.mapView.layoutIfNeeded()
    }

    @MainActor
    private func requestShowcase(_ routes: NavigationRoutes, shouldFit: Bool) {
        pendingShowcase = (routes, shouldFit)
        prepareMapForCurrentLayout()
    }

    @MainActor
    private func flushPendingShowcaseIfPossible() {
        guard let request = pendingShowcase,
              let mapView = navigationMapView
        else { return }

        // NavigationMapView and its inner MapView both start at 64×64. The
        // default mobile camera needs substantially more vertical room for its
        // maneuver/trip-progress padding, so never calculate it at startup
        // size. A normal portrait or landscape phone viewport clears 320pt.
        let size = mapView.mapView.bounds.size
        guard size.width > 64, size.height > 320 else { return }

        pendingShowcase = nil
        mapView.showcase(
            request.routes,
            routesPresentationStyle: .all(shouldFit: request.shouldFit),
            animated: true
        )
    }

    @MainActor
    private func setupMapView() {
        guard !_mapInitialized else { return }
        let provider = ensureProvider()
        let core = provider.mapboxNavigation

        let mapView = NavigationMapView(
            location: core.navigation().locationMatching
                .map(\.enhancedLocation)
                .eraseToAnyPublisher(),
            routeProgress: core.navigation().routeProgress
                .map { $0?.routeProgress }
                .eraseToAnyPublisher(),
            routeRefreshing: core.navigation().routeRefreshing,
            predictiveCacheManager: provider.predictiveCacheManager
        )
        mapView.frame = containerView.bounds
        mapView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        containerView.addSubview(mapView)
        navigationMapView = mapView
        layoutMapView(in: containerView.bounds)
        // Route-preview taps: the map's own tap recognizer hit-tests
        // alternative route lines and reports through this delegate
        // (NavigationMapView+Gestures.didReceiveTap). Without it, alternatives
        // are drawn by showcase() but taps go nowhere — the defect Harry hit.
        mapView.delegate = self
        _mapInitialized = true

        if self.arguments != nil {
            parseFlutterArguments(arguments: arguments)

            if let day = _mapStyleUrlDay, let styleUri = StyleURI(rawValue: day) {
                mapView.mapView.mapboxMap.styleURI = styleUri
            }

            locationManager.requestWhenInUseAuthorization()
        }

        if _longPressDestinationEnabled {
            let gesture = UILongPressGestureRecognizer(target: self, action: #selector(handleLongPress(_:)))
            gesture.delegate = self
            mapView.addGestureRecognizer(gesture)
        }

        sendEvent(eventType: MapBoxEventType.map_ready)
    }

    @MainActor
    func clearRoute(arguments: NSDictionary?, result: @escaping FlutterResult) {
        if navigationRoutes == nil { return }
        NavigationFactory.sharedProvider?.mapboxNavigation.tripSession().setToIdle()
        embeddedGuidanceSubscriptions.removeAll()
        embeddedGuidanceActive = false
        embeddedArrivalSent = false
        navigationMapView?.removeRoutes()
        navigationRoutes = nil
        sendEvent(eventType: MapBoxEventType.navigation_cancelled)
        result(true)
    }

    @MainActor
    func buildRoute(arguments: NSDictionary?, flutterResult: @escaping FlutterResult) {
        _wayPoints.removeAll()
        isEmbeddedNavigation = true
        sendEvent(eventType: MapBoxEventType.route_building)

        guard let locations = getLocationsFromFlutterArgument(arguments: arguments) else {
            flutterResult(false)
            return
        }

        for loc in locations {
            var location = MapboxDirections.Waypoint(
                coordinate: CLLocationCoordinate2D(latitude: loc.latitude!, longitude: loc.longitude!),
                coordinateAccuracy: -1,
                name: loc.name
            )
            location.separatesLegs = !loc.isSilent
            _wayPoints.append(location)
        }

        parseFlutterArguments(arguments: arguments)

        if _wayPoints.count > 3 && arguments?["mode"] == nil {
            _navigationMode = "driving"
        }
        setNavigationOptions(wayPoints: _wayPoints)

        let provider = ensureProvider()
        Task { @MainActor in
            do {
                let routes = try await provider.mapboxNavigation.routingProvider()
                    .calculateRoutes(options: self._options!)
                    .value
                self.navigationRoutes = routes
                self.sendEvent(
                    eventType: MapBoxEventType.route_built,
                    data: self.routeSummariesJson(routes)
                )
                self.requestShowcase(routes, shouldFit: true)
                flutterResult(true)
            } catch {
                self.sendEvent(eventType: MapBoxEventType.route_build_failed)
                flutterResult(false)
            }
        }
    }

    /// Compact per-route summaries for the Dart preview sheet — index, label,
    /// duration and distance, primary first. Matches the Android payload.
    private func routeSummariesJson(_ routes: NavigationRoutes) -> String {
        var all: [[String: Any]] = []
        let main = routes.mainRoute.route
        all.append([
            "index": 0,
            "label": main.description,
            "durationS": main.expectedTravelTime,
            "distanceM": main.distance,
        ])
        for (i, alt) in routes.alternativeRoutes.enumerated() {
            all.append([
                "index": i + 1,
                "label": alt.route.description,
                "durationS": alt.route.expectedTravelTime,
                "distanceM": alt.route.distance,
            ])
        }
        guard let data = try? JSONSerialization.data(withJSONObject: all),
              let json = String(data: data, encoding: .utf8) else { return "[]" }
        return json
    }

    /// Programmatic twin of the preview map tap — index into the order the
    /// last route_built reported: 0 is the primary, 1… are alternatives.
    @MainActor
    func selectRoute(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard _navigationViewController == nil,
              !embeddedGuidanceActive,
              let index = arguments?["index"] as? Int,
              let current = self.navigationRoutes
        else {
            result(false)
            return
        }
        if index == 0 {
            result(true)
            return
        }
        let altIndex = index - 1
        guard current.alternativeRoutes.indices.contains(altIndex) else {
            result(false)
            return
        }
        Task { @MainActor in
            guard let promoted = await current.selectingAlternativeRoute(at: altIndex) else {
                result(false)
                return
            }
            self.navigationRoutes = promoted
            self.requestShowcase(promoted, shouldFit: false)
            self.sendEvent(
                eventType: MapBoxEventType.route_built,
                data: self.routeSummariesJson(promoted)
            )
            result(true)
        }
    }

    @MainActor
    func startEmbeddedFreeDrive(arguments: NSDictionary?, result: @escaping FlutterResult) {
        // Free drive on v3: an active trip session with no routes; the map's
        // location publisher moves the puck.
        let provider = ensureProvider()
        provider.mapboxNavigation.tripSession().startFreeDrive()
        navigationMapView?.update(navigationCameraState: .following)
        result(true)
    }

    @MainActor
    func startEmbeddedNavigation(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let routes = self.navigationRoutes else {
            result(false)
            return
        }
        prepareMapForCurrentLayout()
        let provider = ensureProvider()
        let core = provider.mapboxNavigation

        // Accessing the provider's shared voice controller creates its Core
        // route-progress subscription. It does not require NavigationUIKit.
        _ = provider.routeVoiceController

        embeddedGuidanceSubscriptions.removeAll()
        embeddedArrivalSent = false

        core.navigation().locationMatching
            .map(\.enhancedLocation)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] location in
                self?._lastKnownLocation = location
            }
            .store(in: &embeddedGuidanceSubscriptions)

        core.navigation().routeProgress
            .compactMap { $0?.routeProgress }
            .receive(on: DispatchQueue.main)
            .sink { [weak self] progress in
                guard let self else { return }
                self._distanceRemaining = progress.distanceRemaining
                self._durationRemaining = progress.durationRemaining
                self.sendEvent(eventType: MapBoxEventType.navigation_running)

                if let sink = self._eventSink,
                   let data = try? JSONEncoder().encode(MapBoxRouteProgressEvent(progress: progress)),
                   let json = String(data: data, encoding: .ascii)
                {
                    sink(json)
                }

                if !self.embeddedArrivalSent,
                   progress.isFinalLeg,
                   progress.currentLegProgress.userHasArrivedAtWaypoint
                {
                    self.embeddedArrivalSent = true
                    self.sendEvent(eventType: MapBoxEventType.on_arrival, data: "true")
                }
            }
            .store(in: &embeddedGuidanceSubscriptions)

        embeddedGuidanceActive = true
        core.tripSession().startActiveGuidance(with: routes, startLegIndex: 0)
        navigationMapView?.update(navigationCameraState: .following)
        result(true)
    }

    @MainActor
    private func getVoiceMuted() -> Bool {
        ensureProvider().routeVoiceController.speechSynthesizer.muted
    }

    @MainActor
    private func setVoiceMuted(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let muted = arguments?["muted"] as? Bool else {
            result(FlutterError(
                code: "invalid_voice_state",
                message: "setVoiceMuted requires a boolean muted value",
                details: nil
            ))
            return
        }
        let synthesizer = ensureProvider().routeVoiceController.speechSynthesizer
        synthesizer.muted = muted
        result(synthesizer.muted)
    }

    @MainActor
    override func endNavigation(result: FlutterResult?) {
        embeddedGuidanceSubscriptions.removeAll()
        embeddedGuidanceActive = false
        embeddedArrivalSent = false
        super.endNavigation(result: result)
    }

    func constraintsWithPaddingBetween(holderView: UIView, topView: UIView, padding: CGFloat) {
        guard holderView.subviews.contains(topView) else { return }
        topView.translatesAutoresizingMaskIntoConstraints = false
        let pinTop = NSLayoutConstraint(
            item: topView, attribute: .top, relatedBy: .equal,
            toItem: holderView, attribute: .top, multiplier: 1.0, constant: padding
        )
        let pinBottom = NSLayoutConstraint(
            item: topView, attribute: .bottom, relatedBy: .equal,
            toItem: holderView, attribute: .bottom, multiplier: 1.0, constant: padding
        )
        let pinLeft = NSLayoutConstraint(
            item: topView, attribute: .left, relatedBy: .equal,
            toItem: holderView, attribute: .left, multiplier: 1.0, constant: padding
        )
        let pinRight = NSLayoutConstraint(
            item: topView, attribute: .right, relatedBy: .equal,
            toItem: holderView, attribute: .right, multiplier: 1.0, constant: padding
        )
        holderView.addConstraints([pinTop, pinBottom, pinLeft, pinRight])
    }
}

extension FlutterMapboxNavigationView: NavigationMapViewDelegate {
    /// Route choice happens at preview only — after the routes are built,
    /// before Start (Harry, 2026-08-02; standard Mapbox behaviour). During
    /// guidance the child NavigationViewController owns the map and this
    /// preview map view is covered, so the guard is belt-and-braces.
    public func navigationMapView(
        _ navigationMapView: NavigationMapView,
        didSelect alternativeRoute: AlternativeRoute
    ) {
        guard _navigationViewController == nil, !embeddedGuidanceActive else { return }
        Task { @MainActor in
            guard let current = self.navigationRoutes,
                  let promoted = await current.selecting(alternativeRoute: alternativeRoute)
            else { return }
            self.navigationRoutes = promoted
            self.requestShowcase(promoted, shouldFit: false)
            // Same event the initial build sends: the Dart side re-reads the
            // summaries off it, and Start now begins on the promoted route.
            self.sendEvent(
                eventType: MapBoxEventType.route_built,
                data: self.routeSummariesJson(promoted)
            )
        }
    }
}

extension FlutterMapboxNavigationView: UIGestureRecognizerDelegate {
    public func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        return true
    }

    @objc func handleLongPress(_ gesture: UILongPressGestureRecognizer) {
        MainActor.assumeIsolated {
            guard gesture.state == .ended, let mapView = navigationMapView else { return }
            let location = mapView.mapView.mapboxMap.coordinate(for: gesture.location(in: mapView.mapView))
            requestRoute(destination: location)
        }
    }

    @MainActor
    func requestRoute(destination: CLLocationCoordinate2D) {
        isEmbeddedNavigation = true
        sendEvent(eventType: MapBoxEventType.route_building)

        guard let lastLocation = _lastKnownLocation ?? locationManager.location else { return }
        let userWaypoint = MapboxDirections.Waypoint(location: lastLocation, name: "Current Location")
        let destinationWaypoint = MapboxDirections.Waypoint(coordinate: destination)

        setNavigationOptions(wayPoints: [userWaypoint, destinationWaypoint])

        let provider = ensureProvider()
        Task { @MainActor in
            do {
                let routes = try await provider.mapboxNavigation.routingProvider()
                    .calculateRoutes(options: self._options!)
                    .value
                self.navigationRoutes = routes
                self.sendEvent(
                    eventType: MapBoxEventType.route_built,
                    data: self.routeSummariesJson(routes)
                )
                self.requestShowcase(routes, shouldFit: true)
            } catch {
                self.sendEvent(eventType: MapBoxEventType.route_build_failed)
            }
        }
    }
}
