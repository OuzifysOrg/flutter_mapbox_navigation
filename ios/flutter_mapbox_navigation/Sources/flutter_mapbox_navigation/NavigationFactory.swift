import Flutter
import UIKit
import CoreLocation
import MapboxMaps
import MapboxDirections
import MapboxNavigationCore
import MapboxNavigationUIKit

/// Full-screen turn-by-turn on Navigation SDK v3.
///
/// v2's stack (`Directions.shared`, `MapboxNavigationService`, `RouteResponse`)
/// is gone in v3: one `MapboxNavigationProvider` owns routing, the trip session
/// and voice, routes arrive as `NavigationRoutes` from an async
/// `RoutingProvider`, and `NavigationViewController` (which survives in
/// `MapboxNavigationUIKit`) is constructed from those routes directly.
/// The Flutter-facing contract — channels, arguments, event stream — is
/// unchanged from the v2 fork.
public class NavigationFactory: NSObject, FlutterStreamHandler {
    var _navigationViewController: NavigationViewController? = nil
    var _eventSink: FlutterEventSink? = nil

    var isEmbeddedNavigation = false

    var _distanceRemaining: Double?
    var _durationRemaining: Double?
    var _navigationMode: String?
    var _wayPointOrder = [Int: MapboxDirections.Waypoint]()
    var _wayPoints = [MapboxDirections.Waypoint]()
    var _lastKnownLocation: CLLocation?

    var _options: NavigationRouteOptions?
    var _simulateRoute = false
    var _allowsUTurnAtWayPoints: Bool?
    var _alternatives: Bool?
    var _isOptimized = false
    var _language = "en"
    var _voiceUnits = "imperial"
    var _mapStyleUrlDay: String?
    var _mapStyleUrlNight: String?
    var _zoom: Double = 13.0
    var _tilt: Double = 0.0
    var _bearing: Double = 0.0
    var _animateBuildRoute = true
    var _longPressDestinationEnabled = true
    var _shouldReRoute = true
    var _showReportFeedbackButton = true
    var _showEndOfRouteFeedback = true

    /// The provider is the app-wide owner of navigation state in v3 and is
    /// created ONCE — its `locationSource` (live vs simulated) is fixed at
    /// creation, so the first guidance start decides it. That matches how the
    /// app uses `kSimulateNavigation`: a build-time testing aid, not a
    /// per-drive toggle.
    static var sharedProvider: MapboxNavigationProvider?

    @MainActor
    func ensureProvider() -> MapboxNavigationProvider {
        NavigationFactory.ensureSharedProvider(simulate: _simulateRoute)
    }

    /// The app-wide provider, exposed for **CarPlay**.
    ///
    /// A host app integrating `CarPlayManager` must hand it the SAME
    /// `MapboxNavigationProvider` this plugin uses, or the car and the phone
    /// end up with two navigation sessions and two versions of the truth
    /// about the current route. This is the only supported way to get it.
    ///
    /// - Parameter simulate: only honoured if the provider does not exist
    ///   yet — `locationSource` is fixed at creation.
    @MainActor
    public static func ensureSharedProvider(simulate: Bool = false) -> MapboxNavigationProvider {
        if let provider = sharedProvider { return provider }
        let config = CoreConfig(
            locationSource: simulate ? .simulation(initialLocation: nil) : .live
        )
        let provider = MapboxNavigationProvider(coreConfig: config)
        sharedProvider = provider
        return provider
    }

    /// Scene-aware root view controller lookup. KEPT from the v2 fork: this
    /// app uses the scene-based lifecycle (FlutterSceneDelegate), so
    /// `UIApplication.shared.delegate?.window` is nil and the old force-cast
    /// crashed the moment guidance started.
    func rootFlutterViewController() -> FlutterViewController? {
        let rootVC = UIApplication.shared.delegate?.window??.rootViewController
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }.first(where: { $0.isKeyWindow })?.rootViewController
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }.first?.rootViewController
        return rootVC as? FlutterViewController
    }

    @MainActor
    func addWayPoints(arguments: NSDictionary?, result: @escaping FlutterResult) {
        guard let locations = getLocationsFromFlutterArgument(arguments: arguments) else { return }

        var nextIndex = 1
        for loc in locations {
            var wayPoint = MapboxDirections.Waypoint(
                coordinate: CLLocationCoordinate2D(latitude: loc.latitude!, longitude: loc.longitude!),
                name: loc.name
            )
            wayPoint.separatesLegs = !loc.isSilent
            if _wayPoints.count >= nextIndex {
                _wayPoints.insert(wayPoint, at: nextIndex)
            } else {
                _wayPoints.append(wayPoint)
            }
            nextIndex += 1
        }

        startNavigationWithWayPoints(wayPoints: _wayPoints, flutterResult: result, isUpdatingWaypoints: true)
    }

    func startFreeDrive(arguments: NSDictionary?, result: @escaping FlutterResult) {
        // v2 presented a bespoke FreeDriveViewController. The RevBase app never
        // uses free drive on iOS; rather than ship an unexercised rewrite, this
        // fork reports it unsupported until something actually needs it.
        result(FlutterError(
            code: "unsupported",
            message: "startFreeDrive is not supported by this fork on iOS (SDK v3 rewrite)",
            details: nil
        ))
    }

    @MainActor
    func startNavigation(arguments: NSDictionary?, result: @escaping FlutterResult) {
        _wayPoints.removeAll()
        _wayPointOrder.removeAll()

        guard let locations = getLocationsFromFlutterArgument(arguments: arguments) else { return }

        for loc in locations {
            var location = MapboxDirections.Waypoint(
                coordinate: CLLocationCoordinate2D(latitude: loc.latitude!, longitude: loc.longitude!),
                name: loc.name
            )
            location.separatesLegs = !loc.isSilent
            _wayPoints.append(location)
            _wayPointOrder[loc.order!] = location
        }

        parseFlutterArguments(arguments: arguments)

        if _wayPoints.count > 3 && arguments?["mode"] == nil {
            _navigationMode = "driving"
        }

        if !_wayPoints.isEmpty {
            startNavigationWithWayPoints(wayPoints: _wayPoints, flutterResult: result, isUpdatingWaypoints: false)
        }
    }

    @MainActor
    func startNavigationWithWayPoints(
        wayPoints: [MapboxDirections.Waypoint],
        flutterResult: @escaping FlutterResult,
        isUpdatingWaypoints: Bool
    ) {
        setNavigationOptions(wayPoints: wayPoints)
        sendEvent(eventType: MapBoxEventType.route_building)

        let provider = ensureProvider()
        let mapboxNavigation = provider.mapboxNavigation

        Task { @MainActor in
            do {
                let routes = try await mapboxNavigation.routingProvider()
                    .calculateRoutes(options: self._options!)
                    .value
                self.sendEvent(eventType: MapBoxEventType.route_built)
                if isUpdatingWaypoints {
                    // Mid-drive waypoint update: hand the running trip session
                    // the recalculated routes.
                    mapboxNavigation.tripSession().startActiveGuidance(with: routes, startLegIndex: 0)
                    flutterResult("true")
                } else {
                    self.presentNavigation(with: routes, provider: provider)
                    flutterResult(true)
                }
            } catch {
                self.sendEvent(eventType: MapBoxEventType.route_build_failed)
                flutterResult("An error occured while calculating the route \(error.localizedDescription)")
            }
        }
    }

    @MainActor
    func presentNavigation(with routes: NavigationRoutes, provider: MapboxNavigationProvider) {
        isEmbeddedNavigation = false

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
            predictiveCacheManager: provider.predictiveCacheManager
        )

        let navigationViewController = NavigationViewController(
            navigationRoutes: routes,
            navigationOptions: navigationOptions
        )
        navigationViewController.modalPresentationStyle = .fullScreen
        navigationViewController.delegate = self
        navigationViewController.showsReportFeedback = _showReportFeedbackButton
        navigationViewController.showsEndOfRouteFeedback = _showEndOfRouteFeedback
        _navigationViewController = navigationViewController

        guard let flutterViewController = rootFlutterViewController() else { return }
        flutterViewController.present(navigationViewController, animated: true, completion: nil)
    }

    func setNavigationOptions(wayPoints: [MapboxDirections.Waypoint]) {
        var mode: ProfileIdentifier = .automobileAvoidingTraffic

        if _navigationMode == "cycling" {
            mode = .cycling
        } else if _navigationMode == "driving" {
            mode = .automobile
        } else if _navigationMode == "walking" {
            mode = .walking
        }
        let options = NavigationRouteOptions(waypoints: wayPoints, profileIdentifier: mode)

        if let allowsUTurns = _allowsUTurnAtWayPoints {
            options.allowsUTurnAtWaypoint = allowsUTurns
        }

        // NavigationRouteOptions' init hardcodes includesAlternativeRoutes =
        // true (v3.27.0, Routing/NavigationRouteOptions.swift:60), which is why
        // alternatives appeared even while this flag was ignored. Honouring it
        // matters for the `false` case.
        if let alternatives = _alternatives {
            options.includesAlternativeRoutes = alternatives
        }

        options.distanceMeasurementSystem = _voiceUnits == "imperial" ? .imperial : .metric
        options.locale = Locale(identifier: _language)
        _options = options
    }

    func parseFlutterArguments(arguments: NSDictionary?) {
        _language = arguments?["language"] as? String ?? _language
        _voiceUnits = arguments?["units"] as? String ?? _voiceUnits
        _simulateRoute = arguments?["simulateRoute"] as? Bool ?? _simulateRoute
        _isOptimized = arguments?["isOptimized"] as? Bool ?? _isOptimized
        _allowsUTurnAtWayPoints = arguments?["allowsUTurnAtWayPoints"] as? Bool
        _alternatives = arguments?["alternatives"] as? Bool ?? _alternatives
        _navigationMode = arguments?["mode"] as? String ?? "drivingWithTraffic"
        _showReportFeedbackButton = arguments?["showReportFeedbackButton"] as? Bool ?? _showReportFeedbackButton
        _showEndOfRouteFeedback = arguments?["showEndOfRouteFeedback"] as? Bool ?? _showEndOfRouteFeedback
        _mapStyleUrlDay = arguments?["mapStyleUrlDay"] as? String
        _mapStyleUrlNight = arguments?["mapStyleUrlNight"] as? String
        _zoom = arguments?["zoom"] as? Double ?? _zoom
        _bearing = arguments?["bearing"] as? Double ?? _bearing
        _tilt = arguments?["tilt"] as? Double ?? _tilt
        _animateBuildRoute = arguments?["animateBuildRoute"] as? Bool ?? _animateBuildRoute
        _longPressDestinationEnabled = arguments?["longPressDestinationEnabled"] as? Bool ?? _longPressDestinationEnabled
    }

    @MainActor
    func endNavigation(result: FlutterResult?) {
        sendEvent(eventType: MapBoxEventType.navigation_finished)

        NavigationFactory.sharedProvider?.mapboxNavigation.tripSession().setToIdle()

        if self._navigationViewController != nil {
            if isEmbeddedNavigation {
                self._navigationViewController?.view.removeFromSuperview()
                self._navigationViewController?.removeFromParent()
                self._navigationViewController = nil
            } else {
                self._navigationViewController?.dismiss(animated: true, completion: {
                    self._navigationViewController = nil
                    if result != nil {
                        result!(true)
                    }
                })
            }
        }
    }

    func getLocationsFromFlutterArgument(arguments: NSDictionary?) -> [Location]? {
        var locations = [Location]()
        guard let oWayPoints = arguments?["wayPoints"] as? NSDictionary else { return nil }
        for item in oWayPoints as NSDictionary {
            let point = item.value as! NSDictionary
            guard let oName = point["Name"] as? String else { return nil }
            guard let oLatitude = point["Latitude"] as? Double else { return nil }
            guard let oLongitude = point["Longitude"] as? Double else { return nil }
            let oIsSilent = point["IsSilent"] as? Bool ?? false
            let order = point["Order"] as? Int
            let location = Location(name: oName, latitude: oLatitude, longitude: oLongitude, order: order, isSilent: oIsSilent)
            locations.append(location)
        }
        if !_isOptimized {
            // waypoints must be in the right order
            locations.sort(by: { $0.order ?? 0 < $1.order ?? 0 })
        }
        return locations
    }

    func sendEvent(eventType: MapBoxEventType, data: String = "") {
        let routeEvent = MapBoxRouteEvent(eventType: eventType, data: data)

        let jsonEncoder = JSONEncoder()
        let jsonData = try! jsonEncoder.encode(routeEvent)
        let eventJson = String(data: jsonData, encoding: String.Encoding.utf8)
        if _eventSink != nil {
            _eventSink!(eventJson)
        }
    }

    func downloadOfflineRoute(arguments: NSDictionary?, flutterResult: @escaping FlutterResult) {
        // Offline routing was never implemented in this plugin (v2 carried the
        // same stub, commented out).
        flutterResult(false)
    }

    // MARK: EventListener Delegates

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        _eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        _eventSink = nil
        return nil
    }
}

extension NavigationFactory: NavigationViewControllerDelegate {
    // MARK: NavigationViewController Delegates

    public func navigationViewController(
        _ navigationViewController: NavigationViewController,
        didUpdate progress: RouteProgress,
        with location: CLLocation,
        rawLocation: CLLocation
    ) {
        _lastKnownLocation = location
        _distanceRemaining = progress.distanceRemaining
        _durationRemaining = progress.durationRemaining
        sendEvent(eventType: MapBoxEventType.navigation_running)
        if _eventSink != nil {
            let jsonEncoder = JSONEncoder()

            let progressEvent = MapBoxRouteProgressEvent(progress: progress)
            let progressEventJsonData = try! jsonEncoder.encode(progressEvent)
            let progressEventJson = String(data: progressEventJsonData, encoding: String.Encoding.ascii)

            _eventSink!(progressEventJson)
        }
    }

    public func navigationViewController(
        _ navigationViewController: NavigationViewController,
        didArriveAt waypoint: MapboxDirections.Waypoint
    ) {
        sendEvent(eventType: MapBoxEventType.on_arrival, data: "true")
    }

    public func navigationViewControllerDidDismiss(
        _ navigationViewController: NavigationViewController,
        byCanceling canceled: Bool
    ) {
        if canceled {
            sendEvent(eventType: MapBoxEventType.navigation_cancelled)
        }
        // The SDK calls its delegate on the main thread; assert that so the
        // @MainActor endNavigation can be called from this nonisolated context.
        MainActor.assumeIsolated {
            endNavigation(result: nil)
        }
    }
}
