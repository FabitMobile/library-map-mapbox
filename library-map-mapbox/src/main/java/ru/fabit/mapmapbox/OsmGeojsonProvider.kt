package ru.fabit.mapmapbox

import org.json.JSONArray
import org.json.JSONObject
import ru.fabit.map.dependencies.factory.GeoJsonFactory
import ru.fabit.map.internal.domain.entity.MapBounds

class OsmGeojsonProvider(private val geoJsonFactory: GeoJsonFactory) {
    private val LAYERS = "layers"
    private val FEATURES = "features"
    private val TYPE = "type"
    private val FEATURE_COLLECTION = "FeatureCollection"
    fun createGeoJsonString(mapBounds: MapBounds, selectedMarkerId: Int): String {
        val geojsonListLayersString =
            geoJsonFactory.createGeoJsonString(mapBounds, selectedMarkerId)

        val jsonArray = JSONObject(geojsonListLayersString).getJSONArray(LAYERS)
        val arrayFeatures = JSONArray()
        for (i in 0 until jsonArray.length()) {
            val featuresArray = jsonArray.getJSONObject(i).getJSONArray(FEATURES)
            for (j in 0 until featuresArray.length()) {
                arrayFeatures.put(featuresArray.getJSONObject(j))
            }
        }
        val geojsonObject = JSONObject()
        geojsonObject.put(TYPE, FEATURE_COLLECTION)
        geojsonObject.put(FEATURES, arrayFeatures)
        return geojsonObject.toString()
    }
}