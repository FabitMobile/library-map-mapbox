package ru.fabit.mapmapbox

import ru.fabit.map.internal.domain.entity.marker.Marker

interface LineColorProvider {
    fun getLineColorExpressionItems(selectedMarker: Marker?): List<LineColorExpressionItem>
}

data class LineColorExpressionItem(val property: String, val value: String, val color: Int)