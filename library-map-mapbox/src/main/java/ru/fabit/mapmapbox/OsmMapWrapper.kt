package ru.fabit.mapmapbox

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.os.Handler
import android.os.Looper.getMainLooper
import android.view.View
import com.mapbox.android.core.location.*
import com.mapbox.geojson.*
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.geometry.LatLngBoundsZoom
import com.mapbox.mapboxsdk.location.LocationComponent
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.LocationUpdate
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.MapboxMapOptions
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.plugins.localization.LocalizationPlugin
import com.mapbox.mapboxsdk.plugins.markerview.MarkerViewManager
import com.mapbox.mapboxsdk.style.expressions.Expression
import com.mapbox.mapboxsdk.style.layers.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonOptions
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.utils.ColorUtils
import ru.fabit.map.dependencies.factory.GeoJsonFactory
import ru.fabit.map.dependencies.factory.GeometryColorFactory
import ru.fabit.map.dependencies.factory.MarkerBitmapFactory
import ru.fabit.map.internal.domain.entity.*
import ru.fabit.map.internal.domain.entity.marker.*
import ru.fabit.map.internal.domain.listener.*
import ru.fabit.map.internal.protocol.MapProtocol
import kotlin.math.abs


class OsmMapWrapper(
    private val context: Context,
    key: String,
    private val geoJsonFactory: GeoJsonFactory,
    private val markerBitmapFactory: MarkerBitmapFactory,
    private val geometryColorFactory: GeometryColorFactory,
    private val initColor: Int,
    private val zoomGeojson: Int,
    private val drawablesForAnimated: List<Bitmap>,
    private val mapStyleProvider: OsmMapStyleProviderImpl,
    private val lineColorProvider: LineColorProvider
) : MapProtocol {

    companion object {
        private val idAnimatedImageSource = { id: String -> "anim_marker_${id}" }
        private val idAnimLayer = { id: String -> "id_anim_layer_${id}" }
        private val markersAnimatedSet: HashSet<String> = HashSet()
        private val markersMap: HashMap<String, Marker> = HashMap()
        private var payableZones: List<String> = listOf()
    }

    private var bottomRightY: Float = 0.0f
    private var bottomRightX: Float = 0.0f
    private val colors = mutableMapOf<Int, String>()
    private val geojsonLayerId: String = "geojsonLayerId"
    private val geojsonSourceId: String = "geojsonSourceId"
    private var currentMapBounds: LatLngBoundsZoom? = null
    private var geojsonString: String = ""
    private var initStyle: String = Style.MAPBOX_STREETS
    private var isColoredMarkersEnabled: Boolean = false
    private val MIN_ZOOM: Double = 0.0
    private val MAX_ZOOM: Double = 20.0
    private val DEFAULT_ZOOM: Double = 18.00
    private val COARSE_LOCATION_PERMISSION = ACCESS_COARSE_LOCATION
    private val FINE_LOCATION_PERMISSION = ACCESS_FINE_LOCATION
    private val DEFAULT_DISPLACEMENT = 10f

    private var isDisabledOn: Boolean = false
    private var isRadarOn: Boolean = false
    private val markerLayerId = "markerLayerId"
    private val markerSourceId = "markerSourceId"
    private val polygonLayerId = "polygonLayerId"
    private val polygonOutlineLayerId = "polygonOutlineLayerId"
    private val polygonSourceId = "polygonSourceId"
    private val lineLayerId = "lineLayerId"
    private val lineSourceId = "lineSourceId"
    private val layerIds = listOf(
        markerLayerId,
        polygonLayerId,
        lineLayerId,
        geojsonLayerId,
        polygonOutlineLayerId
    )
    private val ICON_PROPERTY = "ICON_PROPERTY"
    private val COLOR_PROPERTY = "COLOR_PROPERTY"
    private val STROKE_COLOR_PROPERTY = "STROKE_COLOR_PROPERTY"

    private var visibleMapRegionListeners = mutableListOf<VisibleMapRegionListener>()
    private var markerManager: MarkerViewManager? = null
    private var mapView: MapView? = null
    private var isDebug: Boolean = false
    private var mapboxMap: MapboxMap? = null
    private var locationComponent: LocationComponent? = null
    private var mapListeners = mutableListOf<MapListener>()
    private var style: Style? = null
    private val coefficientScaleMapBounds = 1.2f

    private var layoutChangeListeners = mutableListOf<View.OnLayoutChangeListener>()

    private var layoutChangeListener: View.OnLayoutChangeListener? =
        View.OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            this.layoutChangeListeners.forEach {
                it.onLayoutChange(
                    v,
                    left,
                    top,
                    right,
                    bottom,
                    oldLeft,
                    oldTop,
                    oldRight,
                    oldBottom
                )
            }
        }
    private var mapLocationListeners = mutableListOf<MapLocationListener>()
    private var locationEngine: LocationEngine? = null

    private var defaultCoordinates: MapCoordinates? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var zoom: Float? = null
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private val parkingImageProvider: ParkingImageProvider?
    private var latLngSelectedObject: LatLng? = null
    private var offsetFromCenter: Double = 0.0

    private var locationEngineCallback: LocationEngineCallback<LocationEngineResult>? =
        object : LocationEngineCallback<LocationEngineResult> {

            override fun onSuccess(result: LocationEngineResult?) {
                result?.lastLocation?.let { location ->
                    mapLocationListeners.forEach {
                        it.onLocationUpdate(
                            MapCoordinates(
                                location.latitude,
                                location.longitude,
                                location.speed.toDouble() ?: 0.0,
                                location.provider ?: "",
                                location.accuracy ?: 0f
                            )
                        )
                    }
                    val locationUpdate = LocationUpdate.Builder().location(location).build()
                    if (mapboxMap != null) {
                        locationComponent?.forceLocationUpdate(locationUpdate)
                    }
                }
            }

            override fun onFailure(exception: Exception) {
                exception.printStackTrace()
            }
        }

    init {
        Mapbox.getInstance(context, key)
        parkingImageProvider = ParkingImageProvider(markerBitmapFactory)
    }

    override fun isDebugMode(): Boolean {
        return isDebug
    }

    override fun setDebugMode(isDebug: Boolean) {
        this.isDebug = isDebug
    }

    override fun setPayableZones(payableZones: List<String>) {

    }

    override fun setUniqueColorForComParking(uniqueColorForComParking: Boolean) {

    }

    override fun createGeoJsonLayer() {

    }

    override fun disableMap() {
        mapView = null
    }

    override fun enableMap() {
        val options = MapboxMapOptions.createFromAttributes(context)
        options.renderSurfaceOnTop(true)
        mapView = MapView(context, options)
        mapView?.addOnLayoutChangeListener(layoutChangeListener)
    }

    override fun init(style: String) {
        initStyle = style
        initializedMap(style) {
            moveCamera()
        }
    }

    private fun initializedMap(
        style: String,
        onInitializedListener: (mapboxMap: MapboxMap) -> Unit = {}
    ) {
        mapView?.getMapAsync { mapboxMap ->
            mapboxMap.setStyle(
                Style.Builder().fromUri(style)
                    .withSource(GeoJsonSource(markerSourceId))
                    .withSource(GeoJsonSource(polygonSourceId))
                    .withSource(GeoJsonSource(lineSourceId))
                    .withSource(
                        GeoJsonSource(
                            geojsonSourceId,
                            GeoJsonOptions().withMinZoom(zoomGeojson)
                        )
                    )
                    .withLayer(buildLineLayer())
                    .withLayerBelow(LineLayer(geojsonLayerId, geojsonSourceId), lineLayerId)
                    .withLayerAbove(buildMarkerLayer(), lineLayerId)
                    .withLayerBelow(
                        buildPolygonOutLineLayer(),
                        markerLayerId
                    )
                    .withLayerBelow(
                        buildPolygonLayer(),
                        polygonOutlineLayerId
                    )
            ) { style ->
                this.style = style

                drawablesForAnimated.forEachIndexed { index, item ->
                    style.addImage(index.toString(), item)
                }

                enableLocationComponent(style, mapboxMap)

                mapboxMap.setMaxZoomPreference(MAX_ZOOM)

                val localizationPlugin = LocalizationPlugin(mapView!!, mapboxMap, style)
                localizationPlugin.matchMapLanguageWithDeviceDefault()

                mapboxMap.uiSettings.isRotateGesturesEnabled = false
                mapboxMap.uiSettings.isTiltGesturesEnabled = false

                var selectedMarker: Marker? = null
                markersMap.forEach {
                    if (it.value.state == MarkerState.SELECTED) {
                        selectedMarker = it.value
                    }
                }
                updateGeojsonLayer(mapboxMap, selectedMarker)
            }
            mapboxMap.addOnCameraMoveListener(OsmOnCameraMoveListener(mapboxMap))

            markerManager = MarkerViewManager(mapView, mapboxMap)
            markerManager?.onCameraDidChange(false)

            mapClickListener(mapboxMap)

            this.mapboxMap = mapboxMap

            onInitializedListener(mapboxMap)
        }
    }

    private fun buildPolygonLayer(): FillLayer {
        val polygonLayer = FillLayer(polygonLayerId, polygonSourceId)
        polygonLayer.setProperties(
            PropertyFactory.fillColor(
                Expression.get(COLOR_PROPERTY)
            ),
            PropertyFactory.fillOpacity(0.6f)
        )
        return polygonLayer
    }

    private fun buildPolygonOutLineLayer(): LineLayer {
        val polygonOutlineLayer = LineLayer(polygonOutlineLayerId, polygonSourceId)
        polygonOutlineLayer.setProperties(
            PropertyFactory.lineColor(
                Expression.get(STROKE_COLOR_PROPERTY)
            ),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
            PropertyFactory.lineWidth(4.5f)
        )
        return polygonOutlineLayer
    }

    private fun buildLineLayer(): LineLayer {
        val lineLayer = LineLayer(lineLayerId, lineSourceId)
        lineLayer.setProperties(
            PropertyFactory.lineColor(
                Expression.get(COLOR_PROPERTY)
            ),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
            PropertyFactory.lineWidth(4.5f)
        )
        return lineLayer
    }

    private fun buildMarkerLayer(): SymbolLayer {
        val markerLayer = SymbolLayer(markerLayerId, markerSourceId)
        markerLayer.setProperties(
            PropertyFactory.iconImage(
                Expression.get(ICON_PROPERTY)
            ),
            PropertyValue("icon-ignore-placement", true)
        )
        return markerLayer
    }

    private fun updateGeojsonLayer(mapboxMap: MapboxMap, selectedMarker: Marker?) {
        val latLngBoundsZoom = mapboxMap.getLatLngBoundsZoomFromCamera(mapboxMap.cameraPosition)
        latLngBoundsZoom?.let { bounds ->
            val isCameraPositionOutBounds = currentMapBounds?.let { currentBounds ->
                checkCrossingBorder(
                    bounds,
                    currentBounds
                )
            } ?: false
            if (bounds.zoom >= zoomGeojson && !isRadarOn && !isDisabledOn && (isCameraPositionOutBounds || geojsonString.isEmpty())) {
                addGeojson(bounds, selectedMarker)
            } else if (isRadarOn || isDisabledOn) {
                removeGeojson()
            }
        }
    }

    private fun checkCrossingBorder(
        bounds: LatLngBoundsZoom,
        currentMapBounds: LatLngBoundsZoom
    ): Boolean {
        val minLat = currentMapBounds.latLngBounds?.southWest?.latitude!!
        val maxLat = currentMapBounds.latLngBounds?.northWest?.latitude!!
        val minLon = currentMapBounds.latLngBounds?.southWest?.longitude!!
        val maxLon = currentMapBounds.latLngBounds?.southEast?.longitude!!

        val deltaLat = (maxLat - minLat) / 4
        val deltaLon = (maxLon - minLon) / 4

        return bounds.latLngBounds?.center?.longitude!! !in (minLon + deltaLon)..(maxLon - deltaLon)
            || bounds.latLngBounds?.center?.latitude!! !in (minLat + deltaLat)..(maxLat - deltaLat)
    }

    private fun addGeojson(bounds: LatLngBoundsZoom, selectedMarker: Marker?) {
        currentMapBounds = bounds

        geojsonString = geoJsonFactory.createGeoJsonString(
            getScaledMapBounds(
                MapBounds(
                    bounds.latLngBounds?.southWest?.latitude,
                    bounds.latLngBounds?.northWest?.latitude,
                    bounds.latLngBounds?.southWest?.longitude,
                    bounds.latLngBounds?.southEast?.longitude
                )
            ),
            selectedMarker?.data?.id ?: -1
        )

        if (geojsonString.isNotEmpty() && style?.isFullyLoaded == true) {
            (style?.getSource(geojsonSourceId) as? GeoJsonSource)?.setGeoJson(geojsonString)
            updateGeojsonLayerProperties(selectedMarker)
        }
    }

    private fun updateGeojsonLayerProperties(selectedMarker: Marker?) {
        val defaultColorString = getColorStringFromCache(initColor) ?: ""
        (style?.getLayer(geojsonLayerId) as? LineLayer)?.setProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_MITER),
            PropertyFactory.lineWidth(4.5f),
            PropertyFactory.lineColor(
                getExpressionForColoringLines(
                    defaultColorString,
                    selectedMarker
                )
            )
        )
    }

    private fun getExpressionForColoringLines(
        defaultColorString: String,
        selectedMarker: Marker?
    ): Expression {
        val expressionItems = lineColorProvider.getLineColorExpressionItems(selectedMarker)
        return buildExpression(expressionItems.iterator(), defaultColorString)
    }

    private fun buildExpression(
        items: Iterator<LineColorExpressionItem>,
        defaultColor: String
    ): Expression {
        return if (!items.hasNext()) {
            Expression.literal(defaultColor)
        } else {
            val item = items.next()
            Expression.match(
                Expression.get(item.property),
                buildExpression(items, defaultColor),
                Expression.stop(item.value, getColorStringFromCache(item.color) ?: "")
            )
        }
    }

    private fun removeGeojson() {
        if (style?.isFullyLoaded == true) {
            geojsonString = ""
            (style?.getSource(geojsonSourceId) as? GeoJsonSource)?.setGeoJson(
                FeatureCollection.fromFeatures(
                    mutableListOf()
                )
            )
        }
    }

    private fun getScaledMapBounds(mapBounds: MapBounds): MapBounds {
        var minLat = mapBounds.minLat
        var maxLat = mapBounds.maxLat
        var minLon = mapBounds.minLon
        var maxLon = mapBounds.maxLon

        var deltaLat = maxLat - minLat
        var deltaLon = maxLon - minLon

        deltaLat = abs(deltaLat - deltaLat * coefficientScaleMapBounds)
        deltaLon = abs(deltaLon - deltaLon * coefficientScaleMapBounds)

        minLat -= deltaLat
        maxLat += deltaLat
        minLon -= deltaLon
        maxLon += deltaLon

        return MapBounds(minLat, maxLat, minLon, maxLon)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(
        style: Style,
        mapboxMap: MapboxMap
    ) {
        if (locationComponent == null) {
            val locationComponentActivationOptions = LocationComponentActivationOptions
                .builder(context, style)
                .build()
            mapboxMap.locationComponent.activateLocationComponent(locationComponentActivationOptions)
            mapboxMap.locationComponent.isLocationComponentEnabled = true
            mapboxMap.locationComponent.cameraMode = CameraMode.NONE
            mapboxMap.locationComponent.renderMode = RenderMode.NORMAL
            locationComponent = mapboxMap.locationComponent
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locationEngine = LocationEngineProvider.getBestLocationEngine(context)
        val request = LocationEngineRequest.Builder(100)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .setDisplacement(DEFAULT_DISPLACEMENT)
            .build()

        locationEngine?.let { locationEngine ->
            locationEngineCallback?.let { locationEngineCallback ->
                locationEngine.requestLocationUpdates(
                    request,
                    locationEngineCallback,
                    getMainLooper()
                )
                locationEngine.getLastLocation(locationEngineCallback)
            }
        }
    }

    private fun mapClickListener(mapboxMap: MapboxMap) {
        mapboxMap.addOnMapClickListener { point ->

            val pixel = mapboxMap.projection.toScreenLocation(point)
            val features = mapboxMap.queryRenderedFeatures(pixel, *layerIds.toTypedArray())

            var feature: Feature? = null
            val marker = if (!features.isNullOrEmpty()) {
                feature = features.first()
                markersMap[feature?.id()]
            } else null
            if (feature?.geometry()?.type()?.contentEquals(Location.LINE_STRING) == true) {
                mapListeners.forEach {
                    it.onPolyLineClicked(
                        MapPoint(
                            point.longitude,
                            point.latitude
                        )
                    )
                }
                return@addOnMapClickListener false
            }
            if (marker == null) {
                mapListeners.forEach {
                    it.onMapTap(MapPoint(point.longitude, point.latitude))
                }
                false
            } else {
                point.let {
                    mapListeners.forEach {
                        it.onMarkerClicked(marker)
                    }
                }

                true
            }
        }
    }

    private fun moveCamera() {
        val lat = this.latitude
        val long = this.longitude
        val zoom = this.zoom
        if (lat != null && long != null && zoom != null) {
            moveCameraPositionWithZoom(lat, long, zoom)
        }
        moveToUserLocation(defaultCoordinates)
    }

    private fun startAnimatedMarker(marker: Marker) {
        val runnable =
            AnimatedImageRunnable(Handler(), drawablesForAnimated.lastIndex, mapboxMap!!, marker)
        Handler().postDelayed(runnable, (marker as AnimationMarker).delay)
    }

    override fun start() {
        mapView?.onStart()
    }

    override fun stop() {
        mapView?.onStop()
    }

    override fun getMapView(): View? {
        return mapView
    }

    override fun setFocusRect(
        topLeftX: Float,
        topLeftY: Float,
        bottomRightX: Float,
        bottomRightY: Float
    ) {
        this.bottomRightX = bottomRightX
        this.bottomRightY = bottomRightY
        calculateOffsetFromCenter()

        latLngSelectedObject?.let { latLngSelectedObject ->
            moveCameraPositionWithZoom(latLngSelectedObject.latitude, latLngSelectedObject.longitude, zoom ?: DEFAULT_ZOOM.toFloat())
        }
    }

    private fun calculateOffsetFromCenter() {
        mapView?.let { mapView ->
            val centerMapView = PointF(
                mapView.width.toFloat() / 2,
                mapView.height.toFloat() / 2
            )

            val centerOfVisibleMapView = if (bottomRightY > 0) {
                PointF(
                    bottomRightX / 2,
                    bottomRightY / 2
                )
            } else centerMapView

            mapboxMap?.let { mapboxMap ->
                val latLngOfCenterOfVisibleMapView =
                    mapboxMap.projection.fromScreenLocation(centerOfVisibleMapView)
                val latLngOfCenterMapView = mapboxMap.projection.fromScreenLocation(centerMapView)
                offsetFromCenter =
                    latLngOfCenterOfVisibleMapView.latitude - latLngOfCenterMapView.latitude
            }
        }
    }

    override fun clearCache(id: String) {

    }

    override fun updateVersionCache(time: String) {

    }

    @SuppressLint("MissingPermission")
    override fun destroy() {
        visibleMapRegionListeners.clear()
        mapListeners.clear()
        mapLocationListeners.clear()
        mapboxMap?.locationComponent?.isLocationComponentEnabled = false
        mapView?.removeOnLayoutChangeListener(layoutChangeListener)
        layoutChangeListener = null
        layoutChangeListeners.clear()
        locationEngineCallback?.let { locationEngineCallback ->
            locationEngine?.removeLocationUpdates(
                locationEngineCallback
            )
        }
        markerManager?.onDestroy()
        markerManager = null
        mapView?.onPause()
        mapView?.onStop()
        mapView?.onDestroy()
        mapboxMap = null
        locationComponent = null
        style = null
        locationEngine = null
        locationEngineCallback = null
    }

    override fun create(savedInstanceState: Bundle?) {
        mapView?.onCreate(savedInstanceState)
    }

    override fun resume() {
        mapView?.onResume()
    }

    override fun pause() {
        mapView?.onPause()
    }

    override fun saveInstanceState(outState: Bundle) {
        mapView?.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        mapView?.onLowMemory()
    }

    override fun addVisibleMapRegionListener(visibleMapRegionListener: VisibleMapRegionListener) {
        visibleMapRegionListeners.add(visibleMapRegionListener)
    }

    override fun removeVisibleRegionListeners() {
        visibleMapRegionListeners.clear()
    }

    override fun removeVisibleRegionListener(visibleMapRegionListener: VisibleMapRegionListener) {
        visibleMapRegionListeners.remove(visibleMapRegionListener)
    }

    override fun addMapListener(mapListener: MapListener) {
        mapListeners.add(mapListener)
    }

    override fun removeMapListeners() {
        mapListeners.clear()
    }

    override fun removeMapListener(mapListener: MapListener) {
        mapListeners.remove(mapListener)
    }

    override fun addMapLocationListener(mapLocationListener: MapLocationListener) {
        this.mapLocationListeners.add(mapLocationListener)
        startLocationUpdates()
    }

    override fun removeMapLocationListeners() {
        this.mapLocationListeners.clear()
    }

    override fun removeMapLocationListener(mapLocationListener: MapLocationListener) {
        this.mapLocationListeners.remove(mapLocationListener)
    }

    override fun addLayoutChangeListener(layoutChangeListener: View.OnLayoutChangeListener) {
        this.layoutChangeListeners.add(layoutChangeListener)
    }

    override fun removeLayoutChangeListeners() {
        this.layoutChangeListeners.clear()
    }

    override fun removeLayoutChangeListener(layoutChangeListener: View.OnLayoutChangeListener) {
        this.layoutChangeListeners.remove(layoutChangeListener)
    }

    override fun addSizeChangeListener(sizeChangeListener: SizeChangeListener) {

    }

    override fun removeSizeChangeListeners() {

    }

    override fun removeSizeChangeListener(sizeChangeListener: SizeChangeListener) {

    }

    override fun drawQuad(key: String, rect: Rect, color: Int) {

    }

    override fun enableLocation(enable: Boolean?) {

    }

    override fun onMarkersUpdated(
        oldMarkers: MutableMap<String, Marker>,
        newMarkers: MutableMap<String, Marker>,
        zoom: Float
    ) {
        insert(newMarkers.values.toList())
    }

    override fun isAnimatedMarkersEnabled(): Boolean {
        return isRadarOn
    }

    private fun insert(markers: List<Marker>) {
        var selectedMarker: Marker? = null
        markersMap.clear()
        markers.forEach { marker ->
            markersMap[marker.id] = marker
            if (marker.state == MarkerState.SELECTED) {
                selectedMarker = marker
            }
        }
        selectedMarker?.let { marker ->
            if (checkVisibilitySelectedMarker(marker)) {
                markersMap[marker.id]?.state = marker.state
                geojsonString = ""
                mapboxMap?.let { updateGeojsonLayer(it, marker) }
            }
        }

        updateMapObject(markersMap.values.toList())
    }

    private fun updateMapObject(markers: List<Marker>) {
        style?.let { style ->
            val markerFeatures = mutableListOf<Feature>()
            val lineFeatures = mutableListOf<Feature>()
            val polygonFeatures = mutableListOf<Feature>()
            markers.forEach { marker ->
                if (marker.type == MarkerType.ANIMATION && (marker as AnimationMarker).showAnimation) {
                    val _id = marker.id.replace(AnimationMarkerType.ANIMATED.toString(), "")
                    if (!markersAnimatedSet.contains(_id)) {
                        markersAnimatedSet.add(_id)
                        startAnimatedMarker(marker)
                    }
                } else {
                    val bitmapId = addBitmap(style, marker)
                    if (marker.type != MarkerType.NO_MARKER) {
                        if (!(markersMap.contains(marker.id)
                                && mapboxMap?.cameraPosition?.zoom == zoomGeojson.toDouble()
                                && marker.data?.type == MapItemType.PARKING.toString())
                        ) {
                            markerFeatures.add(createSymbolFeature(marker, bitmapId))
                        }
                    }
                    marker.data?.location?.let { location ->
                        when {
                            location.type!!.contentEquals(Location.LINE_STRING) && (isRadarOn || isDisabledOn) -> {
                                lineFeatures.add(
                                    createLineFeature(
                                        marker,
                                        location
                                    )
                                )
                            }
                            location.type!!.contentEquals(Location.POLYGON) -> {
                                polygonFeatures.add(
                                    createPolygonFeature(
                                        marker,
                                        location
                                    )
                                )
                            }
                            else -> null
                        }
                    }
                }
            }

            updateSources(
                style,
                markerFeatures,
                lineFeatures,
                polygonFeatures,
                markerSourceId,
                lineSourceId,
                polygonSourceId
            )
        }
    }

    private fun updateSources(
        style: Style,
        markerFeatures: MutableList<Feature>,
        lineFeatures: MutableList<Feature>,
        polygonFeatures: MutableList<Feature>,
        markerSourceId: String,
        lineSourceId: String,
        polygonSourceId: String
    ) {
        val markerSource = style.getSource(markerSourceId)
        if (markerSource != null && markerSource is GeoJsonSource) {
            markerSource.setGeoJson(FeatureCollection.fromFeatures(markerFeatures))
        }
        val lineSource = style.getSource(lineSourceId)
        if (lineSource != null && lineSource is GeoJsonSource) {
            lineSource.setGeoJson(FeatureCollection.fromFeatures(lineFeatures))
        }
        val polygonSource = style.getSource(polygonSourceId)
        if (polygonSource != null && polygonSource is GeoJsonSource) {
            polygonSource.setGeoJson(FeatureCollection.fromFeatures(polygonFeatures))
        }
    }

    private fun createSymbolFeature(marker: Marker, bitmapId: String): Feature {
        val feature = Feature.fromGeometry(
            Point.fromLngLat(
                marker.longitude,
                marker.latitude
            ), null, marker.id
        )
        feature.addStringProperty(ICON_PROPERTY, bitmapId)
        return feature
    }

    private fun addBitmap(style: Style, marker: Marker): String {
        val bitmapId = parkingImageProvider?.getId(marker) ?: ""
        var bitmap = bitmaps[bitmapId]
        if (bitmap == null) {
            bitmap = parkingImageProvider?.getImage(marker)
        }

        bitmap?.let {
            bitmaps[bitmapId] = it
            style.addImage(bitmapId, it)
        }
        return bitmapId
    }

    private fun getColorStringFromCache(color: Int): String? {
        var colorString = colors[color]
        if (colorString == null) {
            colorString = ColorUtils.colorToRgbaString(color)
            colors[color] = colorString
        }
        return colorString
    }

    private fun createLineFeature(
        marker: Marker,
        location: Location
    ): Feature {
        val color = geometryColorFactory.getColorStrokeGeometry(
            context,
            marker,
            isRadarOn,
            isDisabledOn
        )
        val colorString = getColorStringFromCache(color)
        val feature =
            Feature.fromGeometry(LineString.fromLngLats(location.lineStringMapCoordinates.map {
                Point.fromLngLat(
                    it.longitude,
                    it.latitude
                )
            }), null, marker.id)
        feature.addStringProperty(COLOR_PROPERTY, colorString)
        return feature
    }

    private fun createPolygonFeature(
        marker: Marker,
        location: Location
    ): Feature {
        val color = geometryColorFactory.getColorFillGeometry(
            context,
            marker,
            isRadarOn,
            isDisabledOn
        )
        val strokeColor = geometryColorFactory.getColorStrokeGeometry(
            context,
            marker,
            isRadarOn,
            isDisabledOn
        )
        val colorString = getColorStringFromCache(color)
        val strokeColorString = getColorStringFromCache(strokeColor)
        val feature = Feature.fromGeometry(Polygon.fromLngLats(location.polygonMapCoordinate
            .map {
                it.map { mapCoordinates ->
                    Point.fromLngLat(
                        mapCoordinates.longitude,
                        mapCoordinates.latitude
                    )
                }
            }
        ), null, marker.id)
        feature.addStringProperty(COLOR_PROPERTY, colorString)
        feature.addStringProperty(STROKE_COLOR_PROPERTY, strokeColorString)
        return feature
    }

    override fun moveCameraPosition(latitude: Double, longitude: Double) {
        moveCameraPositionWithZoom(latitude, longitude, DEFAULT_ZOOM.toFloat())
    }

    override fun moveCameraPositionWithZoom(latitude: Double, longitude: Double, zoom: Float) {
        calculateOffsetFromCenter()
        val latLng = LatLng(latitude - offsetFromCenter, longitude)
        latLngSelectedObject = LatLng(latitude, longitude)
        this.latitude = latitude
        this.longitude = longitude
        this.zoom = zoom
        val cameraPosition = CameraPosition.Builder()
            .target(latLng)
            .zoom(zoom.toDouble())
            .bearing(0.0)
            .tilt(0.0)
            .build()

        mapboxMap?.animateCamera(
            CameraUpdateFactory
                .newCameraPosition(cameraPosition), 200
        )
    }

    override fun moveCameraPositionWithBounds(mapBounds: MapBounds) {
        val topRight = LatLng(mapBounds.maxLat, mapBounds.maxLon)
        val bottomLeft = LatLng(mapBounds.minLat, mapBounds.minLon)
        val latLngBounds = LatLngBounds.Builder()
            .include(topRight) // Northeast
            .include(bottomLeft) // Southwest
            .build()

        mapboxMap?.animateCamera(
            CameraUpdateFactory
                .newLatLngBounds(latLngBounds, 0), 200
        )
    }

    override fun moveCameraZoomAndPosition(latitude: Double, longitude: Double, zoom: Float) {
        val latLng = LatLng(latitude - offsetFromCenter, longitude)
        latLngSelectedObject = LatLng(latitude, longitude)
        this.zoom = zoom
        val cameraPosition = CameraPosition.Builder()
            .target(latLng)
            .zoom(zoom.toDouble())
            .bearing(0.0)
            .tilt(0.0)
            .build()

        mapboxMap?.animateCamera(
            CameraUpdateFactory
                .newCameraPosition(cameraPosition), 400
        )
    }

    override fun moveToUserLocation(defaultCoordinates: MapCoordinates?) {
        this.defaultCoordinates = defaultCoordinates
        defaultCoordinates?.let {
            val latLng = LatLng(defaultCoordinates.latitude - offsetFromCenter, defaultCoordinates.longitude)
            latLngSelectedObject = LatLng(defaultCoordinates.latitude, defaultCoordinates.longitude)
            this.zoom = DEFAULT_ZOOM.toFloat()
            val cameraPosition = CameraPosition.Builder()
                .target(latLng)
                .zoom(DEFAULT_ZOOM)
                .bearing(0.0)
                .tilt(0.0)
                .build()

            mapboxMap?.animateCamera(
                CameraUpdateFactory
                    .newCameraPosition(cameraPosition), 200
            )
        }
    }

    override fun moveToUserLocation(zoom: Float, defaultCoordinates: MapCoordinates?) {
        val locationLatLang = getLocationLatLng(defaultCoordinates)
        locationLatLang?.let {
            val latLng = LatLng(locationLatLang.latitude - offsetFromCenter, locationLatLang.longitude)
            latLngSelectedObject = LatLng(locationLatLang.latitude, locationLatLang.longitude)
            this.zoom = zoom
            val newCameraPosition = CameraPosition.Builder()
                .target(latLng)
                .zoom(zoom.toDouble())
                .bearing(0.0)
                .tilt(0.0)
                .build()

            mapboxMap?.animateCamera(
                CameraUpdateFactory
                    .newCameraPosition(newCameraPosition), 200
            )
        } ?: kotlin.run {
            mapListeners.forEach {
                it.onLocationDisabled()
            }
        }
    }

    private fun getLocationLatLng(mapCoordinates: MapCoordinates?): LatLng? {
        var locationLatLng: LatLng? = null
        var lastKnownLocation: android.location.Location? = null
        if (locationComponent?.isLocationComponentActivated == true) {
            lastKnownLocation = locationComponent?.lastKnownLocation
        }
        if (mapCoordinates != null) {
            locationLatLng = LatLng(mapCoordinates.latitude, mapCoordinates.longitude)
        } else if (lastKnownLocation != null) {
            locationLatLng = LatLng(lastKnownLocation.latitude, lastKnownLocation.longitude)
        }

        return locationLatLng
    }

    override fun tryMoveToUserLocation(
        zoom: Float,
        defaultCoordinates: MapCoordinates,
        mapCallback: MapCallback
    ) {
        val location = locationComponent?.lastKnownLocation
        var zoomCameraPosition: CameraPosition? = null
        if (defaultCoordinates != null) {
            zoomCameraPosition = CameraPosition.Builder()
                .target(LatLng(defaultCoordinates.latitude, defaultCoordinates.longitude))
                .zoom(zoom.toDouble())
                .bearing(0.0)
                .tilt(0.0)
                .build()
        } else if (location != null) {
            zoomCameraPosition = CameraPosition.Builder()
                .target(LatLng(location.latitude, location.longitude))
                .zoom(zoom.toDouble())
                .bearing(0.0)
                .tilt(0.0)
                .build()
        }

        zoomCameraPosition?.let {
            mapboxMap?.animateCamera(
                CameraUpdateFactory
                    .newCameraPosition(zoomCameraPosition), 200
            )
        }
    }

    override fun deselect(markerToDeselect: Marker) {
        geojsonString = ""
        mapboxMap?.let { updateGeojsonLayer(it, null) }
        updateMapObject(markersMap.values.toMutableList())
        latLngSelectedObject = null
    }

    override fun selectMarker(markerToSelect: Marker) {
        geojsonString = ""
        mapboxMap?.let { updateGeojsonLayer(it, markerToSelect) }
        updateMapObject(markersMap.values.toMutableList())
    }

    override fun zoomIn() {
        val cameraPosition = mapboxMap?.cameraPosition
        cameraPosition?.let { position ->
            calculateOffsetFromCenter()

            val latLng =
                LatLng(position.target.latitude + offsetFromCenter / 2, position.target.longitude)
            var zoom = position.zoom
            val bearing = position.bearing
            val tilt = position.tilt
            if (zoom < MAX_ZOOM) {
                zoom += 1
            }
            val newCameraPosition = CameraPosition.Builder()
                .target(latLng)
                .zoom(zoom.roundToInt().toDouble())
                .bearing(bearing)
                .tilt(tilt)
                .build()

            mapboxMap?.animateCamera(
                CameraUpdateFactory
                    .newCameraPosition(newCameraPosition), 200
            )
        }
    }

    override fun zoomOut() {
        val cameraPosition = mapboxMap?.cameraPosition
        cameraPosition?.let { position ->
            calculateOffsetFromCenter()

            val latLng =
                LatLng(position.target.latitude - offsetFromCenter, position.target.longitude)
            var zoom = position.zoom
            val bearing = position.bearing
            val tilt = position.tilt
            if (zoom > MIN_ZOOM) {
                zoom -= 1
            }
            val newCameraPosition = CameraPosition.Builder()
                .target(latLng)
                .zoom(zoom.roundToInt().toDouble())
                .bearing(bearing)
                .tilt(tilt)
                .build()

            mapboxMap?.animateCamera(
                CameraUpdateFactory
                    .newCameraPosition(newCameraPosition), 200
            )
        }
    }

    override fun getVisibleRegionByZoomAndPoint(
        zoom: Float,
        latitude: Double,
        longitude: Double
    ): MapBounds {
        return MapBounds(0.0, 0.0, 0.0, 0.0)
    }

    override fun radarStateChange(
        isRadarOn: Boolean,
        isColoredMarkersEnabled: Boolean,
        markers: Collection<Marker>
    ) {
        this.isRadarOn = isRadarOn
        this.isColoredMarkersEnabled = isColoredMarkersEnabled

        mapboxMap?.getStyle {
            insert(markers.toList())
        }
    }

    override fun onDisabledChange(isDisabledOn: Boolean) {
        var selectedMarker: Marker? = null
        markersMap.forEach {
            if (it.value.state == MarkerState.SELECTED) {
                selectedMarker = it.value
            }
        }
        this.isDisabledOn = isDisabledOn
        updateMapObject(markersMap.values.toMutableList())
        mapboxMap?.let { updateGeojsonLayer(it, selectedMarker) }
    }

    override fun setAnimationMarkerListener(animationMarkerListener: AnimationMarkerListener) {

    }

    override fun setStyle(style: String) {
        initializedMap(style)
    }

    override fun getMapStyleProvider() = mapStyleProvider

    private class AnimatedImageRunnable(
        val handler: Handler,
        val lastIndex: Int,
        val mapboxMap: MapboxMap,
        val marker: Marker
    ) : Runnable {

        private var drawableIndex: Int = 0

        override fun run() {
            val idMarker = marker.id.replace(AnimationMarkerType.ANIMATED.toString(), "")
            mapboxMap.getStyle { style ->
                style.getLayer(idAnimLayer(idMarker))?.let {
                    if (drawableIndex > lastIndex) {
                        markersAnimatedSet.remove(idMarker)
                        style.removeLayer(idAnimLayer(idMarker))
                        style.removeSource(idAnimatedImageSource(idMarker))
                    } else {
                        it.setProperties(PropertyFactory.iconImage(drawableIndex++.toString()))
                        handler.postDelayed(this, 600)
                    }
                } ?: run {
                    val feature = Feature.fromGeometry(
                        Point.fromLngLat(marker.longitude, marker.latitude),
                        null,
                        marker.id
                    )

                    style.addSource(
                        GeoJsonSource(
                            idAnimatedImageSource(idMarker),
                            FeatureCollection.fromFeature(feature)
                        )
                    )

                    style.addLayer(
                        SymbolLayer(idAnimLayer(idMarker), idAnimatedImageSource(idMarker))
                            .withProperties(
                                PropertyFactory.iconImage(drawableIndex++.toString())
                            )
                    )
                    handler.postDelayed(this, 600)
                }
            }
        }
    }

    private fun checkVisibilitySelectedMarker(selectedMarker: Marker): Boolean {
        return markersMap[selectedMarker.id] != null
    }

    inner class OsmOnCameraMoveListener(val mapboxMap: MapboxMap) : MapboxMap.OnCameraMoveListener {

        override fun onCameraMove() {
            var selectedMarker: Marker? = null
            markersMap.forEach {
                if (it.value.state == MarkerState.SELECTED) {
                    selectedMarker = it.value
                }
            }
            updateGeojsonLayer(mapboxMap, selectedMarker)
            val latLngBoundsZoom =
                mapboxMap.getLatLngBoundsZoomFromCamera(mapboxMap.cameraPosition)
            latLngBoundsZoom?.let { bounds ->

                visibleMapRegionListeners.forEach { listener ->
                    listener.onRegionChange(
                        VisibleMapRegion(
                            MapPoint(
                                bounds.latLngBounds.northWest.longitude,
                                bounds.latLngBounds.northWest.latitude
                            ),
                            MapPoint(
                                bounds.latLngBounds.northEast.longitude,
                                bounds.latLngBounds.northEast.latitude
                            ),
                            MapPoint(
                                bounds.latLngBounds.southWest.longitude,
                                bounds.latLngBounds.southWest.latitude
                            ),
                            MapPoint(
                                bounds.latLngBounds.southEast.longitude,
                                bounds.latLngBounds.southEast.latitude
                            ),
                            bounds.zoom.toInt().toFloat()
                        )
                    )
                }
            }
        }
    }

    inner class ParkingImageProvider internal constructor(
        private val markerBitmapFactory: MarkerBitmapFactory
    ) {

        fun getId(marker: Marker): String {
            val needUniqueColor = payableZones.contains(marker.data?.zoneNumber)
            return markerBitmapFactory.getBitmapMapObjectId(
                context,
                marker,
                isRadarOn,
                isDisabledOn,
                isColoredMarkersEnabled,
                needUniqueColor
            )
        }

        fun getImage(marker: Marker): Bitmap? {
            val needUniqueColor = payableZones.contains(marker.data?.zoneNumber)
            val bitmap = markerBitmapFactory.getBitmapMapObject(
                context,
                marker,
                isRadarOn,
                isDisabledOn,
                isColoredMarkersEnabled,
                needUniqueColor
            )
            return bitmap
        }

        //endregion
    }
}