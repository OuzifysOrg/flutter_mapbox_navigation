// swift-tools-version: 5.9

// Flutter SPM plugin manifest. Navigation SDK v3 is distributed via Swift
// Package Manager ONLY (CocoaPods support never shipped for v3), so the
// plugin's CocoaPods podspec is gone and this package replaces it. The host
// app must build plugins with Flutter's Swift Package Manager support — the
// RevBase app already does for every other plugin.
//
// `import Flutter` resolves without an explicit dependency here: the Flutter
// tool injects the framework search paths when it assembles the generated
// plugin package (same as first-party plugins like image_picker_ios).

import PackageDescription

let package = Package(
    name: "flutter_mapbox_navigation",
    platforms: [
        .iOS("14.0") // MapboxNavigationCore's floor
    ],
    products: [
        .library(name: "flutter-mapbox-navigation", targets: ["flutter_mapbox_navigation"])
    ],
    dependencies: [
        .package(
            url: "https://github.com/mapbox/mapbox-navigation-ios.git",
            exact: "3.27.0"
        )
    ],
    targets: [
        .target(
            name: "flutter_mapbox_navigation",
            dependencies: [
                .product(name: "MapboxNavigationCore", package: "mapbox-navigation-ios"),
                .product(name: "MapboxNavigationUIKit", package: "mapbox-navigation-ios"),
            ]
        )
    ]
)
