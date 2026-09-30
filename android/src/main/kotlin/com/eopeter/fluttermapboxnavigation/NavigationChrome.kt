package com.eopeter.fluttermapboxnavigation

import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.eopeter.fluttermapboxnavigation.databinding.NavigationActivityBinding
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverExitOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverPrimaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSecondaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSubOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverViewOptions
import com.mapbox.navigation.ui.components.tripprogress.model.TripProgressViewOptions

/**
 * RevBase's tokens on Mapbox's phone guidance chrome, shared by the embedded
 * view and NavigationActivity (RevBase #243, #247).
 *
 * Everything is set through each view's public options with fork-owned
 * styles, never by redefining Mapbox's colour names: android-auto-components
 * redefines some of those names (black banner text at night) and whichever
 * library wins the resource merge decides what every screen shows.
 */
internal object NavigationChrome {

    fun apply(context: Context, binding: NavigationActivityBinding) {
        binding.maneuverView.updateManeuverViewOptions(
            ManeuverViewOptions.Builder()
                .primaryManeuverOptions(
                    ManeuverPrimaryOptions.Builder()
                        .textAppearance(R.style.RevBaseManeuverPrimary)
                        .exitOptions(exitOptions(R.style.RevBaseExitPrimary))
                        .build()
                )
                .secondaryManeuverOptions(
                    ManeuverSecondaryOptions.Builder()
                        .textAppearance(R.style.RevBaseManeuverSecondary)
                        .exitOptions(exitOptions(R.style.RevBaseExitSecondary))
                        .build()
                )
                .subManeuverOptions(
                    ManeuverSubOptions.Builder()
                        .textAppearance(R.style.RevBaseManeuverSub)
                        .exitOptions(exitOptions(R.style.RevBaseExitSub))
                        .build()
                )
                .build()
        )

        val ink = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.revbase_nav_ink))
        binding.tripProgressView.updateOptions(
            TripProgressViewOptions.Builder()
                .backgroundColor(R.color.revbase_nav_surface)
                .timeRemainingTextAppearance(R.style.RevBaseTripTimeRemaining)
                .distanceRemainingTextAppearance(R.style.RevBaseTripDistanceRemaining)
                .estimatedArrivalTimeTextAppearance(R.style.RevBaseTripArrivalTime)
                .distanceRemainingIconTint(ink)
                .estimatedArrivalTimeIconTint(ink)
                .build()
        )

        binding.recenter.updateStyle(R.style.RevBaseRecenterButton)
        binding.routeOverview.updateStyle(R.style.RevBaseRouteOverviewButton)
        binding.soundButton.updateStyle(R.style.RevBaseSoundButton)

        // The same names as the iOS overlay's buttons, so a screen reader
        // announces each control instead of "button".
        binding.recenter.contentDescription =
            context.getString(R.string.revbase_nav_recenter_description)
        binding.routeOverview.contentDescription =
            context.getString(R.string.revbase_nav_overview_description)
        describeSound(context, binding, muted = false)
    }

    /** The sound button's label names what a tap does next. */
    fun describeSound(context: Context, binding: NavigationActivityBinding, muted: Boolean) {
        binding.soundButton.contentDescription = context.getString(
            if (muted) R.string.revbase_nav_unmute_description
            else R.string.revbase_nav_mute_description
        )
    }

    private fun exitOptions(textAppearance: Int): ManeuverExitOptions =
        ManeuverExitOptions.Builder().textAppearance(textAppearance).build()
}
