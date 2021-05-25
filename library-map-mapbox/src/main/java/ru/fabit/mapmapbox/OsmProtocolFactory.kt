package ru.fabit.mapmapbox

import android.content.Context
import android.graphics.Bitmap
import ru.fabit.map.dependencies.factory.GeoJsonFactory
import ru.fabit.map.dependencies.factory.GeometryColorFactory
import ru.fabit.map.dependencies.factory.MarkerBitmapFactory
import ru.fabit.map.internal.protocol.MapProtocol

class OsmProtocolFactory {
    companion object {
        fun create(
            context: Context,
            key: String,
            geoJsonFactory: GeoJsonFactory,
            markerBitmapFactory: MarkerBitmapFactory,
            geometryColorFactory: GeometryColorFactory,
            initColor: Int,
            zoomGeojson: Int,
            drawablesForAnimated: List<Bitmap>,
            mapStyleProvider: OsmMapStyleProviderImpl,
            lineColorProvider: LineColorProvider
        ): MapProtocol {
            return OsmMapWrapper(
                context,
                key,
                geoJsonFactory,
                markerBitmapFactory,
                geometryColorFactory,
                initColor,
                zoomGeojson,
                drawablesForAnimated,
                mapStyleProvider,
                lineColorProvider
            )
        }
    }
}