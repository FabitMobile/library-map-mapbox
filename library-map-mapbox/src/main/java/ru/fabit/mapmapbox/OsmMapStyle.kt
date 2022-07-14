package ru.fabit.mapmapbox

import com.mapbox.maps.Style

object OsmMapStyle {
    // IMPORTANT: If you change any of these you also need to edit them in strings.xml
    const val MAPBOX_STREETS = Style.MAPBOX_STREETS

    /**
     * Outdoors: A general-purpose style tailored to outdoor activities. Using this constant means
     * your map style will always use the latest version and may change as we improve the style.
     */
    const val OUTDOORS = Style.OUTDOORS

    /**
     * Light: Subtle light backdrop for data visualizations. Using this constant means your map
     * style will always use the latest version and may change as we improve the style.
     */
    const val LIGHT = Style.LIGHT

    /**
     * Dark: Subtle dark backdrop for data visualizations. Using this constant means your map style
     * will always use the latest version and may change as we improve the style.
     */
    const val DARK = Style.DARK

    /**
     * Satellite: A beautiful global satellite and aerial imagery layer. Using this constant means
     * your map style will always use the latest version and may change as we improve the style.
     */
    const val SATELLITE = Style.SATELLITE

    /**
     * Satellite Streets: Global satellite and aerial imagery with unobtrusive labels. Using this
     * constant means your map style will always use the latest version and may change as we
     * improve the style.
     */
    const val SATELLITE_STREETS = Style.SATELLITE_STREETS

    /**
     * Traffic Day: Color-coded roads based on live traffic congestion data. Traffic data is currently
     * available in
     * [these select
 * countries](https://www.mapbox.com/help/how-directions-work/#traffic-data). Using this constant means your map style will always use the latest version and
     * may change as we improve the style.
     */
    const val TRAFFIC_DAY = Style.TRAFFIC_DAY

    /**
     * Traffic Night: Color-coded roads based on live traffic congestion data, designed to maximize
     * legibility in low-light situations. Traffic data is currently available in
     * [these select
 * countries](https://www.mapbox.com/help/how-directions-work/#traffic-data). Using this constant means your map style will always use the latest version and
     * may change as we improve the style.
     */
    const val TRAFFIC_NIGHT = Style.TRAFFIC_NIGHT
}