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
///  * active guidance: a child `NavigationViewController` added into the
///    container, constructed exactly like the full-screen one;
///  * progress events flow through `NavigationViewControllerDelegate`
///    (v2's `NavigationServiceDelegate` route is gone).
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
            } else if call.method == "selectRoute" {
                strongSelf.selectRoute(arguments: arguments, result: result)
            } else if call.method == "reCenter" {
                strongSelf.navigationMapView?.update(navigationCameraState: .following)
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
        if let guidanceController = _navigationViewController {
            layoutGuidanceView(guidanceController, in: bounds)
            return
        }
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
    private func layoutGuidanceView(
        _ controller: NavigationViewController,
        in bounds: CGRect
    ) {
        controller.view.frame = bounds
        containerView.setNeedsLayout()
        containerView.layoutIfNeeded()
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        controller.navigationMapView?.setNeedsLayout()
        controller.navigationMapView?.layoutIfNeeded()
        controller.navigationMapView?.mapView.setNeedsLayout()
        controller.navigationMapView?.mapView.layoutIfNeeded()
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
        guard let routes = self.navigationRoutes,
              let previewMapView = self.navigationMapView
        else {
            result(false)
            return
        }
        prepareMapForCurrentLayout()
        let provider = ensureProvider()

        var dayStyle: DayStyle = CustomDayStyle()
        if _mapStyleUrlDay != nil {
            dayStyle = CustomDayStyle(url: _mapStyleUrlDay)
        }
        let nightStyle = CustomNightStyle()
        if _mapStyleUrlNight != nil {
            nightStyle.mapStyleURL = URL(string: _mapStyleUrlNight!)!
        }

        let navigationOptions = NavigationOptions(
            mapboxNavigation: provider.mapboxNavigation,
            voiceController: provider.routeVoiceController,
            eventsManager: provider.eventsManager(),
            styles: [dayStyle, nightStyle],
            predictiveCacheManager: provider.predictiveCacheManager,
            // Reuse the already-visible, correctly-sized preview map. Creating
            // a second NavigationMapView here leaves Mapbox at its 64×64
            // startup rectangle while viewDidLoad applies guidance padding,
            // producing a black map even though voice guidance is active.
            navigationMapView: previewMapView
        )

        // Remove a previous child controller, if any.
        if _navigationViewController?.view != nil {
            _navigationViewController!.view.removeFromSuperview()
            _navigationViewController?.removeFromParent()
        }

        let navigationViewController = NavigationViewController(
            navigationRoutes: routes,
            navigationOptions: navigationOptions
        )
        navigationViewController.delegate = self
        navigationViewController.showsReportFeedback = _showReportFeedbackButton
        navigationViewController.showsEndOfRouteFeedback = _showEndOfRouteFeedback
        _navigationViewController = navigationViewController

        guard let flutterViewController = rootFlutterViewController() else {
            result(false)
            return
        }
        flutterViewController.addChild(navigationViewController)
        navigationViewController.view.frame = containerView.bounds
        containerView.addSubview(navigationViewController.view)
        navigationViewController.view.translatesAutoresizingMaskIntoConstraints = false
        constraintsWithPaddingBetween(holderView: containerView, topView: navigationViewController.view, padding: 0.0)
        navigationViewController.didMove(toParent: flutterViewController)
        layoutGuidanceView(navigationViewController, in: containerView.bounds)
        navigationViewController.navigationMapView?.navigationCamera.update(
            cameraState: .following
        )
        result(true)
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
        guard _navigationViewController == nil else { return }
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
