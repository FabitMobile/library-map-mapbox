package ru.fabit.mapmapbox

import ru.fabit.map.dependencies.provider.MapStyleProvider

class OsmMapStyleProviderImpl(
    private val defaultStyle: String,
    private val monochromeStyle: String
) : MapStyleProvider {

    override fun getDefaultStyle() = defaultStyle

    override fun getCongestionStyle() = monochromeStyle
}