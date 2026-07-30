import MapboxMaps
import MapboxNavigationUIKit

/// v3 note: `DayStyle` already defaults to the SDK's navigation day style
/// (Maps v11 removed `StyleURI.navigationDay`), so this subclass only
/// overrides the URL when the caller supplied one.
class CustomDayStyle: DayStyle {

    required init() {
        super.init()
        styleType = .day
    }

    init(url: String?) {
        super.init()
        styleType = .day
        if let url, let custom = URL(string: url) {
            mapStyleURL = custom
            previewMapStyleURL = custom
        }
    }
}
