import MapboxMaps
import MapboxNavigationUIKit

/// v3 note: `NightStyle` already defaults to the SDK's navigation night style
/// (Maps v11 removed `StyleURI.navigationNight`), so this subclass only
/// overrides the URL when the caller supplied one.
class CustomNightStyle: NightStyle {

    required init() {
        super.init()
        styleType = .night
    }

    init(url: String?) {
        super.init()
        styleType = .night
        if let url, let custom = URL(string: url) {
            mapStyleURL = custom
            previewMapStyleURL = custom
        }
    }
}
