package ru.fabit.mapmapbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper.getMainLooper
import android.view.View
import com.mapbox.android.core.location.*
import com.mapbox.geojson.*
import com.mapbox.maps.*
import com.mapbox.maps.extension.localization.localizeLabels
import com.mapbox.maps.extension.style.expressions.dsl.generated.match
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.*
import com.mapbox.maps.extension.style.layers.getLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSource
import com.mapbox.maps.extension.style.style
import com.mapbox.maps.extension.style.utils.ColorUtils
import com.mapbox.maps.plugin.animation.CameraAnimatorChangeListener
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.flyTo
import com.mapbox.maps.plugin.gestures.OnMapClickListener
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import com.mapbox.maps.plugin.gestures.gestures
import com.mapbox.maps.plugin.locationcomponent.location
import ru.fabit.map.dependencies.factory.GeoJsonFactory
import ru.fabit.map.dependencies.factory.GeometryColorFactory
import ru.fabit.map.dependencies.factory.MarkerBitmapFactory
import ru.fabit.map.internal.domain.entity.*
import ru.fabit.map.internal.domain.entity.marker.*
import ru.fabit.map.internal.domain.listener.*
import ru.fabit.map.internal.protocol.MapProtocol
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToInt

class OsmMapWrapper(
    private val context: Context,
    private val key: String,
    private val geoJsonFactory: GeoJsonFactory,
    private val markerBitmapFactory: MarkerBitmapFactory,
    private val geometryColorFactory: GeometryColorFactory,
    private val initColor: Int,
    private val zoomGeojson: Int,
    private val drawablesForAnimated: List<Bitmap>,
    private val mapStyleProvider: OsmMapStyleProviderImpl,
    private val lineColorProvider: LineColorProvider,
    private val permissionProvider: PermissionProvider
) : MapProtocol {

    companion object {
        private val idAnimatedImageSource = { id: String -> "anim_marker_${id}" }
        private val idAnimLayer = { id: String -> "id_anim_layer_${id}" }
        private val markersAnimatedSet: HashSet<String> = HashSet()
        private val markersMap: HashMap<String, Marker> = HashMap()
        private var payableZones: List<String> = listOf()
    }

    private var bottomRightY: Float = 0.0f
    private val colors = mutableMapOf<Int, String>()
    private val geojsonLayerId: String = "geojsonLayerId"
    private val geojsonSourceId: String = "geojsonSourceId"
    private var geojsonMapBoundsZoom: CoordinateBoundsZoom? = null
    private var geojsonString: String = ""
    private var initStyle: String = Style.MAPBOX_STREETS
    private var isColoredMarkersEnabled: Boolean = false
    private val MIN_ZOOM: Double = 0.0
    private val MAX_ZOOM: Double = 20.0
    private val DEFAULT_ZOOM: Double = 18.00
    private val DURATION_200MS: Long = 200L
    private val DEFAULT_DISPLACEMENT = 10f
    private val uiThreadHandler: Handler

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

    private var mapView: MapView? = null
    private var isDebug: Boolean = false

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
    private var lastLocation: android.location.Location? = null

    private var locationEngineCallback: LocationEngineCallback<LocationEngineResult>? =
        object : LocationEngineCallback<LocationEngineResult> {

            override fun onSuccess(result: LocationEngineResult?) {
                result?.lastLocation?.let { location ->
                    mapLocationListeners.forEach {
                        it.onLocationUpdate(
                            MapCoordinates(
                                location.latitude,
                                location.longitude,
                                location.speed.toDouble(),
                                location.provider ?: "",
                                location.accuracy
                            )
                        )
                    }
                    lastLocation = location
                }
            }

            override fun onFailure(exception: Exception) {
                exception.printStackTrace()
            }
        }

    init {
        parkingImageProvider = ParkingImageProvider(markerBitmapFactory)
        val mainLooper = getMainLooper()
        this.uiThreadHandler = Handler(mainLooper)
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
        val resourceOptions = ResourceOptionsManager.getDefault(context, key).resourceOptions
        val options = MapInitOptions(
            context = context,
            textureView = true,
            resourceOptions = resourceOptions
        )
        mapView = MapView(context = context, mapInitOptions = options)
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
        mapView?.getMapboxMap()?.loadStyle(styleExtension = style(style) {
            +geoJsonSource(markerSourceId)
            +geoJsonSource(polygonSourceId)
            +geoJsonSource(lineSourceId)
            +geoJsonSource(geojsonSourceId)
            +buildLineLayer()
            +layerAtPosition(
                layer = lineLayer(
                    geojsonLayerId,
                    geojsonSourceId
                ) { minZoom(zoomGeojson.toDouble()) },
                below = lineLayerId
            )
            +layerAtPosition(
                layer = buildMarkerLayer(),
                above = lineLayerId
            )
            +layerAtPosition(
                layer = buildPolygonOutLineLayer(),
                below = markerLayerId
            )
            +layerAtPosition(
                layer = buildPolygonLayer(),
                below = polygonOutlineLayerId
            )
        }) { loadedStyle ->
            this.style = loadedStyle
            mapView?.getMapboxMap()?.apply {
                drawablesForAnimated.forEachIndexed { index, item ->
                    loadedStyle.addImage(index.toString(), item)
                }
                enableLocationComponent()
                mapView?.gestures?.rotateEnabled = false
                mapView?.gestures?.pitchEnabled = false
                loadedStyle.localizeLabels(Locale.getDefault())
                setBounds(
                    CameraBoundsOptions.Builder()
                        .maxZoom(MAX_ZOOM)
                        .build()
                )
                var selectedMarker: Marker? = null
                markersMap.forEach {
                    if (it.value.state == MarkerState.SELECTED) {
                        selectedMarker = it.value
                    }
                }
                val boundsZoom = getCoordinateBoundsZoomWithoutContentPaddings(this)
                updateGeojsonLayer(boundsZoom, selectedMarker)
                mapView?.camera?.addCameraCenterChangeListener(OsmOnCameraMoveListener(this))
                mapView?.camera?.addCameraZoomChangeListener(OsmOnCameraScaleListener(this))
                addOnMapClickListener(OsmOnMapClickListener(this))
                onInitializedListener(this)
            }
        }
    }

    private fun buildPolygonLayer(): FillLayer {
        val polygonLayer = FillLayer(polygonLayerId, polygonSourceId)
        polygonLayer.fillColor(
            Expression.get(COLOR_PROPERTY)
        )
        polygonLayer.fillOpacity(0.6)
        return polygonLayer
    }

    private fun buildPolygonOutLineLayer(): LineLayer {
        val polygonOutlineLayer = LineLayer(polygonOutlineLayerId, polygonSourceId)
        polygonOutlineLayer.lineColor(
            Expression.get(STROKE_COLOR_PROPERTY)
        )
        polygonOutlineLayer.lineCap(LineCap.ROUND)
        polygonOutlineLayer.lineJoin(LineJoin.MITER)
        polygonOutlineLayer.lineWidth(4.5)
        return polygonOutlineLayer
    }

    private fun buildLineLayer(): LineLayer {
        val lineLayer = LineLayer(lineLayerId, lineSourceId)
        lineLayer.lineColor(
            Expression.get(COLOR_PROPERTY)
        )
        lineLayer.lineCap(LineCap.ROUND)
        lineLayer.lineJoin(LineJoin.MITER)
        lineLayer.lineWidth(4.5)
        return lineLayer
    }

    private fun buildMarkerLayer(): SymbolLayer {
        val markerLayer = SymbolLayer(markerLayerId, markerSourceId)
        markerLayer.iconImage(Expression.get(ICON_PROPERTY))
        markerLayer.iconIgnorePlacement(true)
        return markerLayer
    }

    private fun updateGeojsonLayer(boundsZoom: CoordinateBoundsZoom, selectedMarker: Marker?) {
        val isCameraPositionOutBounds = geojsonMapBoundsZoom?.let { geojsonBounds ->
            checkCrossingBorder(
                boundsZoom,
                geojsonBounds
            )
        } ?: false
        if (boundsZoom.zoom >= zoomGeojson && !isRadarOn && !isDisabledOn && (isCameraPositionOutBounds || geojsonString.isEmpty())) {
            addGeojson(boundsZoom, selectedMarker)
        } else if (isRadarOn || isDisabledOn) {
            removeGeojson()
        }
    }

    private fun checkCrossingBorder(
        boundsZoom: CoordinateBoundsZoom,
        currentMapBoundsZoom: CoordinateBoundsZoom
    ): Boolean {
        val minLat = currentMapBoundsZoom.bounds.southwest.latitude()
        val maxLat = currentMapBoundsZoom.bounds.northeast.latitude()
        val minLon = currentMapBoundsZoom.bounds.southwest.longitude()
        val maxLon = currentMapBoundsZoom.bounds.northeast.longitude()

        val deltaLat = (maxLat - minLat) / 4
        val deltaLon = (maxLon - minLon) / 4

        return boundsZoom.bounds.center().longitude() !in (minLon + deltaLon)..(maxLon - deltaLon)
                || boundsZoom.bounds.center()
            .latitude() !in (minLat + deltaLat)..(maxLat - deltaLat)
    }

    private fun addGeojson(boundsZoom: CoordinateBoundsZoom, selectedMarker: Marker?) {
        geojsonMapBoundsZoom = boundsZoom

        geojsonString = geoJsonFactory.createGeoJsonString(
            mapBounds = getScaledMapBounds(
                MapBounds(
                    boundsZoom.bounds.southwest.latitude(),
                    boundsZoom.bounds.northeast.latitude(),
                    boundsZoom.bounds.southwest.longitude(),
                    boundsZoom.bounds.northeast.longitude()
                )
            ),
            selectedMarkerId = selectedMarker?.data?.id ?: -1
        )

        if (geojsonString.isNotEmpty() && style?.isStyleLoaded == true) {
            (style?.getSource(geojsonSourceId) as? GeoJsonSource)?.featureCollection(
                FeatureCollection.fromJson(geojsonString)
            )
            updateGeojsonLayerProperties(selectedMarker)
        }
    }

    private fun updateGeojsonLayerProperties(selectedMarker: Marker?) {
        val defaultColorString = getColorStringFromCache(initColor) ?: ""
        (style?.getLayer(geojsonLayerId) as? LineLayer)?.apply {
            lineCap(LineCap.ROUND)
            lineJoin(LineJoin.MITER)
            lineWidth(4.5)
            lineColor(
                getExpressionForColoringLines(
                    defaultColorString,
                    selectedMarker
                )
            )
        }
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
            match {
                get(item.property)
                stop {
                    literal(item.value)
                    literal(getColorStringFromCache(item.color) ?: "")
                }
                addArgument(buildExpression(items, defaultColor))
            }
        }
    }

    private fun removeGeojson() {
        if (style?.isStyleLoaded == true) {
            geojsonString = ""
            (style?.getSource(geojsonSourceId) as? GeoJsonSource)?.featureCollection(
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

        deltaLat = abs(deltaLat - deltaLat * coefficientScaleMapBounds * 2)
        deltaLon = abs(deltaLon - deltaLon * coefficientScaleMapBounds)

        minLat -= deltaLat
        maxLat += deltaLat
        minLon -= deltaLon
        maxLon += deltaLon

        return MapBounds(minLat, maxLat, minLon, maxLon)
    }

    @SuppressLint("MissingPermission")
    private fun enableLocationComponent() {
        if (permissionProvider.isLocationPermissionGranted()) {
            startLocationUpdates()
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
        mapView?.getMapboxMap()?.let { mapboxMap ->
            val runnable =
                AnimatedImageRunnable(
                    uiThreadHandler,
                    drawablesForAnimated.lastIndex,
                    mapboxMap,
                    marker
                )
            uiThreadHandler.postDelayed(runnable, (marker as AnimationMarker).delay)
        }
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
        this.bottomRightY = (mapView?.height ?: 0) - bottomRightY
        updateCamera()
    }

    override fun clearCache(id: String) {}

    override fun updateVersionCache(time: String) {}

    @SuppressLint("MissingPermission")
    override fun destroy() {
        visibleMapRegionListeners.clear()
        mapListeners.clear()
        mapLocationListeners.clear()
        mapView?.location?.enabled = false
        mapView?.removeOnLayoutChangeListener(layoutChangeListener)
        layoutChangeListener = null
        layoutChangeListeners.clear()
        locationEngineCallback?.let { locationEngineCallback ->
            locationEngine?.removeLocationUpdates(
                locationEngineCallback
            )
        }
        mapView?.onStop()
        mapView?.onDestroy()
        style = null
        locationEngine = null
        locationEngineCallback = null
    }

    override fun create(savedInstanceState: Bundle?) {
    }

    override fun resume() {}

    override fun pause() {}

    override fun saveInstanceState(outState: Bundle) {}

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

    @SuppressLint("MissingPermission")
    override fun enableLocation(enable: Boolean?) {
        mapView?.location?.enabled =
            permissionProvider.isLocationPermissionGranted()
    }

    override fun onMarkersUpdated(
        oldMarkers: MutableMap<String, Marker>,
        newMarkers: MutableMap<String, Marker>,
        zoom: Float
    ) {
        uiThreadHandler.post {
            insert(newMarkers.values.toList())
        }
    }

    override fun isAnimatedMarkersEnabled(): Boolean {
        return isRadarOn
    }

    override fun drawPolygon(coordinates: List<MapCoordinates>) {

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
                mapView?.getMapboxMap()?.let { mapboxMap ->
                    val bounds = getCoordinateBoundsZoomWithoutContentPaddings(mapboxMap)
                    updateGeojsonLayer(bounds, marker)
                }
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
                        val currentZoom = mapView?.getMapboxMap()?.cameraState?.zoom
                        if (!(markersMap.contains(marker.id)
                                    && currentZoom == zoomGeojson.toDouble()
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
            markerSource.featureCollection(FeatureCollection.fromFeatures(markerFeatures))
        }
        val lineSource = style.getSource(lineSourceId)
        if (lineSource != null && lineSource is GeoJsonSource) {
            val lineFeatureCollection = FeatureCollection.fromFeatures(lineFeatures)
            lineSource.featureCollection(lineFeatureCollection)
        }
        val polygonSource = style.getSource(polygonSourceId)
        if (polygonSource != null && polygonSource is GeoJsonSource) {
            polygonSource.featureCollection(FeatureCollection.fromFeatures(polygonFeatures))
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

    private fun updateCamera() {
        val latitude = this.latitude
        val longitude = this.longitude
        val zoom = this.zoom?.toDouble() ?: DEFAULT_ZOOM
        val builder = CameraOptions.Builder()
        if (latitude != null && longitude != null) {
            val latLng = Point.fromLngLat(longitude, latitude)
            builder.center(latLng)
        }
        val padding = EdgeInsets(0.0, 0.0, this.bottomRightY.toDouble(), 0.0)
        val cameraOptions = builder
            .zoom(zoom)
            .padding(padding)
            .build()
        mapView?.getMapboxMap()?.flyTo(
            cameraOptions,
            MapAnimationOptions.mapAnimationOptions { duration(DURATION_200MS) })
    }

    override fun moveCameraPosition(latitude: Double, longitude: Double) {
        uiThreadHandler.post {
            moveCameraPositionWithZoom(latitude, longitude, DEFAULT_ZOOM.toFloat())
        }
    }

    override fun moveCameraPositionWithZoom(latitude: Double, longitude: Double, zoom: Float) {
        this.latitude = latitude
        this.longitude = longitude
        this.zoom = zoom
        updateCamera()
    }

    override fun moveCameraPositionWithBounds(mapBounds: MapBounds) {
        val topRight = Point.fromLngLat(mapBounds.maxLon, mapBounds.maxLat)
        val bottomLeft = Point.fromLngLat(mapBounds.minLon, mapBounds.minLat)
        val latLngBounds = CoordinateBounds(bottomLeft, topRight)
        mapView?.getMapboxMap()?.let {
            val cameraOptions = it.cameraForCoordinateBounds(latLngBounds)
            it.setCamera(
                cameraOptions
            )
        }
    }

    override fun moveCameraZoomAndPosition(latitude: Double, longitude: Double, zoom: Float) {
        this.latitude = latitude
        this.longitude = longitude
        this.zoom = zoom
        updateCamera()
    }

    override fun moveToUserLocation(defaultCoordinates: MapCoordinates?) {
        this.defaultCoordinates = defaultCoordinates
        defaultCoordinates?.let {
            this.latitude = defaultCoordinates.latitude
            this.longitude = defaultCoordinates.longitude
            this.zoom = DEFAULT_ZOOM.toFloat()
            updateCamera()
        }
    }

    override fun moveToUserLocation(zoom: Float, defaultCoordinates: MapCoordinates?) {
        val locationLatLang = getLocationPoint(defaultCoordinates)
        locationLatLang?.let {
            this.latitude = locationLatLang.latitude()
            this.longitude = locationLatLang.longitude()
            this.zoom = zoom
            updateCamera()
        } ?: kotlin.run {
            mapListeners.forEach {
                it.onLocationDisabled()
            }
        }
    }

    private fun getLocationPoint(mapCoordinates: MapCoordinates?): Point? {
        var locationLatLng: Point? = null
        if (mapCoordinates != null) {
            locationLatLng = Point.fromLngLat(mapCoordinates.longitude, mapCoordinates.latitude)
        } else {
            lastLocation?.let { location ->
                locationLatLng =
                    Point.fromLngLat(location.longitude, location.latitude)
            }
        }
        return locationLatLng
    }

    override fun tryMoveToUserLocation(
        zoom: Float,
        defaultCoordinates: MapCoordinates,
        mapCallback: MapCallback
    ) {
        var isCameraUpdateAvailable = false
        this.zoom = zoom
        updateCamera()
        isCameraUpdateAvailable = true
        this.latitude = defaultCoordinates.latitude
        this.longitude = defaultCoordinates.longitude
        if (isCameraUpdateAvailable) {
            updateCamera()
        }
    }

    override fun deselect(markerToDeselect: Marker) {
        uiThreadHandler.post {
            this.latitude = null
            this.longitude = null
            geojsonString = ""
            mapView?.getMapboxMap()?.let { mapboxMap ->
                val bounds = getCoordinateBoundsZoomWithoutContentPaddings(mapboxMap)
                updateGeojsonLayer(bounds, null)
            }
            updateMapObject(markersMap.values.toMutableList())
        }
    }

    override fun selectMarker(markerToSelect: Marker) {
        uiThreadHandler.post {
            geojsonString = ""
            mapView?.getMapboxMap()?.let { mapboxMap ->
                val bounds = getCoordinateBoundsZoomWithoutContentPaddings(mapboxMap)
                updateGeojsonLayer(bounds, markerToSelect)
            }
            updateMapObject(markersMap.values.toMutableList())
        }
    }

    override fun zoomIn() {
        val cameraState = mapView?.getMapboxMap()?.cameraState
        cameraState?.let { state ->
            var zoom = state.zoom
            if (zoom < MAX_ZOOM) {
                zoom += 1
            }
            this.latitude = state.center.latitude()
            this.longitude = state.center.longitude()
            this.zoom = zoom.roundToInt().toFloat()
            updateCamera()
        }
    }

    override fun zoomOut() {
        val cameraState = mapView?.getMapboxMap()?.cameraState
        cameraState?.let { position ->
            var zoom = position.zoom
            if (zoom > MIN_ZOOM) {
                zoom -= 1
            }
            this.latitude = position.center.latitude()
            this.longitude = position.center.longitude()
            this.zoom = zoom.roundToInt().toFloat()
            updateCamera()
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

        mapView?.getMapboxMap()?.getStyle {
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
        mapView?.getMapboxMap()?.let { mapboxMap ->
            val bounds = getCoordinateBoundsZoomWithoutContentPaddings(mapboxMap)
            updateGeojsonLayer(bounds, selectedMarker)
        }
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
                style.getLayer(idAnimLayer(idMarker))?.also {
                    if (drawableIndex > lastIndex) {
                        markersAnimatedSet.remove(idMarker)
                        style.removeStyleLayer(idAnimLayer(idMarker))
                        style.removeStyleSource(idAnimatedImageSource(idMarker))
                    } else if (it is SymbolLayer) {
                        it.iconImage(drawableIndex++.toString())
                        handler.postDelayed(this, 600)
                    }
                } ?: run {
                    val feature = Feature.fromGeometry(
                        Point.fromLngLat(marker.longitude, marker.latitude),
                        null,
                        marker.id
                    )
                    val source = geoJsonSource(idAnimatedImageSource(idMarker))
                    source.featureCollection(FeatureCollection.fromFeature(feature))
                    style.addSource(source)

                    val layer =
                        symbolLayer(idAnimLayer(idMarker), idAnimatedImageSource(idMarker)) {
                            iconImage(drawableIndex++.toString())
                        }
                    style.addLayer(layer)
                    handler.postDelayed(this, 600)
                }
            }
        }
    }

    private fun checkVisibilitySelectedMarker(selectedMarker: Marker): Boolean {
        return markersMap[selectedMarker.id] != null
    }

    private fun getCoordinateBoundsZoomWithoutContentPaddings(mapboxMap: MapboxMap): CoordinateBoundsZoom {
        val cameraState = mapboxMap.cameraState
        val bounds = mapboxMap.coordinateBoundsForCamera(cameraState.toCameraOptions())
        val zoom = mapboxMap.cameraState.zoom
        return CoordinateBoundsZoom(
            bounds,
            zoom
        )
    }

    private fun updateVisibleMapRegion(mapboxMap: MapboxMap) {
        var selectedMarker: Marker? = null
        markersMap.forEach {
            if (it.value.state == MarkerState.SELECTED) {
                selectedMarker = it.value
            }
        }
        val boundsZoom = getCoordinateBoundsZoomWithoutContentPaddings(mapboxMap)
        updateGeojsonLayer(boundsZoom, selectedMarker)

        visibleMapRegionListeners.forEach { listener ->
            listener.onRegionChange(
                VisibleMapRegion(
                    MapPoint(
                        boundsZoom.bounds.west(),
                        boundsZoom.bounds.north()
                    ),
                    MapPoint(
                        boundsZoom.bounds.east(),
                        boundsZoom.bounds.north()
                    ),
                    MapPoint(
                        boundsZoom.bounds.west(),
                        boundsZoom.bounds.south()
                    ),
                    MapPoint(
                        boundsZoom.bounds.east(),
                        boundsZoom.bounds.south()
                    ),
                    boundsZoom.zoom.toInt().toFloat()
                )
            )
        }
    }

    inner class OsmOnCameraMoveListener(val mapboxMap: MapboxMap) :
        CameraAnimatorChangeListener<Point> {
        override fun onChanged(updatedValue: Point) {
            updateVisibleMapRegion(mapboxMap)
        }
    }

    inner class OsmOnCameraScaleListener(val mapboxMap: MapboxMap) :
        CameraAnimatorChangeListener<Double> {
        override fun onChanged(updatedValue: Double) {
            updateVisibleMapRegion(mapboxMap)
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
    }

    inner class OsmOnMapClickListener(private val mapboxMap: MapboxMap) : OnMapClickListener {
        override fun onMapClick(point: Point): Boolean {
            val screenCoordinate = mapboxMap.pixelForCoordinate(point)
            mapboxMap.queryRenderedFeatures(
                RenderedQueryGeometry(screenCoordinate),
                RenderedQueryOptions(layerIds, null)
            ) { expected ->
                val features = expected.value
                var feature: Feature? = null
                val marker = if (!features.isNullOrEmpty()) {
                    feature = features.firstOrNull()?.feature
                    markersMap[feature?.id()]
                } else {
                    null
                }
                val isLine =
                    feature?.geometry()?.type()?.contentEquals(Location.LINE_STRING) ?: false
                if (marker == null || isLine) {
                    mapListeners.forEach {
                        it.onMapTap(MapPoint(point.longitude(), point.latitude()))
                    }
                } else {
                    point.let {
                        mapListeners.forEach {
                            it.onMarkerClicked(marker)
                        }
                    }
                }
            }
            return false
        }
    }
}
