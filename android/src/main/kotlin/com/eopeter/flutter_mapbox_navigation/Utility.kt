package com.eopeter.flutter_mapbox_navigation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.location.Location
import android.os.Bundle
import androidx.lifecycle.LifecycleOwner
import com.eopeter.flutter_mapbox_navigation.models.MapBoxEvents
import com.eopeter.flutter_mapbox_navigation.models.MapBoxRouteProgressEvent
import com.eopeter.flutter_mapbox_navigation.models.Waypoint
import com.eopeter.flutter_mapbox_navigation.models.WaypointSet
import com.eopeter.flutter_mapbox_navigation.utilities.PluginUtilities
import com.google.gson.Gson
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.geojson.Point
import com.mapbox.maps.*
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.*
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.lifecycle.MapboxNavigationApp
import eopeter.flutter_mapbox_navigation.databinding.NavigationActivityBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.*
import android.util.Log
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.core.trip.session.*
import com.mapbox.navigation.dropin.map.MapViewObserver
import com.mapbox.navigation.dropin.navigationview.NavigationViewListener

open class TurnByTurn(ctx: Context, act: Activity, bind: NavigationActivityBinding, accessToken: String):  MethodChannel.MethodCallHandler, EventChannel.StreamHandler,
        Application.ActivityLifecycleCallbacks {

    open fun initFlutterChannelHandlers() {
        methodChannel?.setMethodCallHandler(this)
        eventChannel?.setStreamHandler(this)
    }

    open fun initNavigation() {
        val navigationOptions = NavigationOptions.Builder(context)
                .accessToken(token)
                .build()

        MapboxNavigationApp
                .setup(navigationOptions)
                .attach(activity as LifecycleOwner)

        binding.navigationView.customizeViewBinders {
            infoPanelEndNavigationButtonBinder
        }

        // initialize navigation trip observers
        registerObservers()
    }

    override fun onMethodCall(methodCall: MethodCall, result: MethodChannel.Result) {
        when (methodCall.method) {
            "getPlatformVersion" -> {
                result.success("Android ${android.os.Build.VERSION.RELEASE}")
            }
            "enableOfflineRouting" -> {
                //downloadRegionForOfflineRouting(call, result)
            }
            "buildRoute" -> {
                buildRoute(methodCall, result)
            }
            "clearRoute" -> {
                clearRoute(methodCall, result)
            }
            "moveUserLocation" -> {
                moveUserLocation(methodCall, result)
            }
            "startNavigation" -> {
                startNavigation(methodCall, result)
            }
            "finishNavigation" -> {
                finishNavigation(methodCall, result)
            }
            "getDistanceRemaining" -> {
                result.success(distanceRemaining)
            }
            "getDurationRemaining" -> {
                result.success(durationRemaining)
            }
            else -> result.notImplemented()
        }
    }

    private fun buildRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        isNavigationCanceled = false

        val arguments = methodCall.arguments as? Map<*, *>
        if(arguments != null)
            setOptions(arguments)

        addedWaypoints.clear()
        val points = arguments?.get("wayPoints") as HashMap<*, *>
        for (item in points)
        {
            val point = item.value as HashMap<*, *>
            val latitude = point["Latitude"] as Double
            val longitude = point["Longitude"] as Double
            addedWaypoints.add(Waypoint(Point.fromLngLat(longitude, latitude)))
        }
        getRoute(context)
        result.success(true)
    }

    private fun getRoute(context: Context) {
        MapboxNavigationApp.current()!!.requestRoutes(
                routeOptions = RouteOptions
                        .builder()
                        .applyDefaultNavigationOptions()
                        .applyLanguageAndVoiceUnitOptions(context)
                        .coordinatesList(addedWaypoints.coordinatesList())
                        .waypointIndicesList(addedWaypoints.waypointsIndices())
                        .waypointNamesList(addedWaypoints.waypointsNames())
                        .alternatives(true)
                        .build(),
                callback = object : NavigationRouterCallback {
                    override fun onRoutesReady(
                            routes: List<NavigationRoute>,
                            routerOrigin: RouterOrigin
                    ) {
                        currentRoutes = routes
                        val directionsRoutes = routes.map { it.directionsRoute }
                        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILT, Gson().toJson(directionsRoutes))
                        binding.navigationView.api.routeReplayEnabled(simulateRoute)
                        binding.navigationView.api.startRoutePreview(routes)
                    }

                    override fun onFailure(
                            reasons: List<RouterFailure>,
                            routeOptions: RouteOptions
                    ) {
                        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_FAILED)
                    }

                    override fun onCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
                        PluginUtilities.sendEvent(MapBoxEvents.ROUTE_BUILD_CANCELLED)
                        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
                    }
                }
        )
    }

    @OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
    private fun clearRoute(methodCall: MethodCall, result: MethodChannel.Result) {
        currentRoutes = null;
        binding.navigationView.api.startFreeDrive()
        MapboxNavigationApp.current()!!.setNavigationRoutes(listOf())
        MapboxNavigationApp.current()!!.mapboxReplayer.stop()
        // send navigation cancelled event to flutter
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
    }

    private fun moveUserLocation(methodCall: MethodCall, result: MethodChannel.Result) {
        val arguments = methodCall.arguments as? Map<*, *>
        if (arguments != null) {
            val latitude = arguments["Latitude"] as Double
            val longitude = arguments["Longitude"] as Double

            setInitialCamera(Point.fromLngLat(longitude, latitude))
        }
    }

    private fun setInitialCamera(coordinate: Point, animated: Boolean = true) {
        val mapAnimationOptions = MapAnimationOptions.Builder().duration(1500L).build()
        mapboxMap.setCamera(CameraOptions.Builder().center(coordinate).zoom(zoom).build())
        mapboxMap.easeTo(CameraOptions.Builder().center(coordinate).zoom(zoom).build(), mapAnimationOptions);
    }

    private fun startNavigation(methodCall: MethodCall, result: MethodChannel.Result) {

        val arguments = methodCall.arguments as? Map<*, *>
        if(arguments != null)
            setOptions(arguments)

        startNavigation()

        if (currentRoutes != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    private fun finishNavigation(methodCall: MethodCall, result: MethodChannel.Result) {

        finishNavigation()

        if (currentRoutes != null) {
            result.success(true)
        } else {
            result.success(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNavigation() {
        binding.navigationView.api.startActiveGuidance(currentRoutes!!);
    }

    private fun finishNavigation(isOffRouted: Boolean = false) {

        if (!isOffRouted && currentRoutes != null) {
            binding.navigationView.api.startFreeDrive()
            binding.navigationView.api.startRoutePreview(currentRoutes!!)
        }
        else {
            currentRoutes = null;
            MapboxNavigationApp.current()!!.stopTripSession()
        }
        isNavigationCanceled = true
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_FINISHED)
    }

    private fun setOptions(arguments: Map<*, *>)
    {
        val navMode = arguments["mode"] as? String
        if(navMode != null)
        {
            if(navMode == "walking")
                navigationMode = DirectionsCriteria.PROFILE_WALKING;
            else if(navMode == "cycling")
                navigationMode = DirectionsCriteria.PROFILE_CYCLING;
            else if(navMode == "driving")
                navigationMode = DirectionsCriteria.PROFILE_DRIVING;
        }

        val simulated = arguments["simulateRoute"] as? Boolean
        if (simulated != null) {
            simulateRoute = simulated
        }

        val language = arguments["language"] as? String
        if(language != null)
            navigationLanguage = language

        val units = arguments["units"] as? String

        if(units != null)
        {
            if(units == "imperial")
                navigationVoiceUnits = DirectionsCriteria.IMPERIAL
            else if(units == "metric")
                navigationVoiceUnits = DirectionsCriteria.METRIC
        }

        mapStyleUrlDay = arguments?.get("mapStyleUrlDay") as? String
        mapStyleUrlNight = arguments?.get("mapStyleUrlNight") as? String

        initialLatitude = arguments["initialLatitude"] as? Double
        initialLongitude = arguments["initialLongitude"] as? Double

        val zm = arguments["zoom"] as? Double
        if(zm != null)
            zoom = zm

        val br = arguments["bearing"] as? Double
        if(br != null)
            bearing = br

        val tt = arguments["tilt"] as? Double
        if(tt != null)
            tilt = tt

        val optim = arguments["isOptimized"] as? Boolean
        if(optim != null)
            isOptimized = optim

        val anim = arguments["animateBuildRoute"] as? Boolean
        if(anim != null)
            animateBuildRoute = anim

        val altRoute = arguments["alternatives"] as? Boolean
        if(altRoute != null)
            alternatives = altRoute

        val voiceEnabled = arguments["voiceInstructionsEnabled"] as? Boolean
        if(voiceEnabled != null)
            voiceInstructionsEnabled = voiceEnabled

        val bannerEnabled = arguments["bannerInstructionsEnabled"] as? Boolean
        if(bannerEnabled != null)
            bannerInstructionsEnabled = bannerEnabled

        val longPress = arguments["longPressDestinationEnabled"] as? Boolean
        if(longPress != null)
            longPressDestinationEnabled = longPress
    }

    open fun registerObservers() {
        // register event listeners
        binding.navigationView.registerMapObserver(mapViewObserver)
        binding.navigationView.addListener(navigationStateListener)
        MapboxNavigationApp.current()?.registerLocationObserver(locationObserver)
        MapboxNavigationApp.current()?.registerRouteProgressObserver(routeProgressObserver)
        MapboxNavigationApp.current()?.registerArrivalObserver(arrivalObserver)
    }

    open fun unregisterObservers() {
        // unregister event listeners to prevent leaks or unnecessary resource consumption
        binding.navigationView.unregisterMapObserver(mapViewObserver)
        binding.navigationView.removeListener(navigationStateListener)
        MapboxNavigationApp.current()?.unregisterLocationObserver(locationObserver)
        MapboxNavigationApp.current()?.unregisterRouteProgressObserver(routeProgressObserver)
        MapboxNavigationApp.current()?.unregisterArrivalObserver(arrivalObserver)
    }

    /**
     * Notifies with attach and detach events on [MapView]
     */
    private val mapViewObserver = object : MapViewObserver() {
        override fun onAttached(mapView: MapView) {
            mapboxMap = mapView.getMapboxMap()
        }

        override fun onDetached(mapView: MapView) {

        }
    }


    //Flutter stream listener delegate methods
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        FlutterMapboxNavigationPlugin.eventSink = events
    }

    override fun onCancel(arguments: Any?) {
        PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
        FlutterMapboxNavigationPlugin.eventSink = null
    }

    private val context: Context = ctx
    val activity: Activity = act
    private val token: String = accessToken
    open var methodChannel: MethodChannel? = null
    open var eventChannel: EventChannel? = null
    private var lastLocation: Location? = null

    /**
     * Helper class that keeps added waypoints and transforms them to the [RouteOptions] params.
     */
    private val addedWaypoints = WaypointSet()

    //Config
    var initialLatitude: Double? = null
    var initialLongitude: Double? = null

    //val wayPoints: MutableList<Point> = mutableListOf()
    var navigationMode =  DirectionsCriteria.PROFILE_DRIVING_TRAFFIC
    var simulateRoute = false
    var mapStyleUrlDay: String? = null
    var mapStyleUrlNight: String? = null
    var navigationLanguage = "en"
    var navigationVoiceUnits = DirectionsCriteria.IMPERIAL
    var zoom = 15.0
    var bearing = 0.0
    var tilt = 0.0
    var distanceRemaining: Float? = null
    var durationRemaining: Double? = null

    var alternatives = true

    var allowsUTurnAtWayPoints = false
    var enableRefresh = false
    var voiceInstructionsEnabled = true
    var bannerInstructionsEnabled = true
    var longPressDestinationEnabled = true
    var animateBuildRoute = true
    private var isOptimized = false

    private var currentRoutes:  List<NavigationRoute>? = null
    private var isNavigationCanceled = false

    /**
     * Bindings to the example layout.
     */
    open val binding: NavigationActivityBinding = bind

    /**
     * Mapbox Maps entry point obtained from the [MapView].
     * You need to get a new reference to this object whenever the [MapView] is recreated.
     */
    private lateinit var mapboxMap: MapboxMap

    /**
     * Gets notified with location updates.
     *
     * Exposes raw updates coming directly from the location services
     * and the updates enhanced by the Navigation SDK (cleaned up and matched to the road).
     */
    private val locationObserver = object : LocationObserver {
        override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
            val enhancedLocation = locationMatcherResult.enhancedLocation
            lastLocation = locationMatcherResult.enhancedLocation
        }

        override fun onNewRawLocation(rawLocation: Location) {
            // no impl
        }
    }

    /**
     * Gets notified with location updates.
     *
     * Exposes raw updates coming directly from the location services
     * and the updates enhanced by the Navigation SDK (cleaned up and matched to the road).
     */
    private val navigationStateListener = object : NavigationViewListener() {

        override fun onRouteFetchCanceled(routeOptions: RouteOptions, routerOrigin: RouterOrigin) {
            PluginUtilities.sendEvent(MapBoxEvents.NAVIGATION_CANCELLED)
        }

        override fun onArrival() {
            PluginUtilities.sendEvent(MapBoxEvents.ON_ARRIVAL)
        }
    }

    /**
     * Gets notified with progress along the currently active route.
     */
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        //update flutter events
        if (!isNavigationCanceled) {
            try {

                distanceRemaining = routeProgress.distanceRemaining
                durationRemaining = routeProgress.durationRemaining

                val progressEvent = MapBoxRouteProgressEvent(routeProgress)
                PluginUtilities.sendEvent(progressEvent)

            } catch (e: java.lang.Exception) {

            }
        } else {

        }
    }

    private val arrivalObserver: ArrivalObserver = object : ArrivalObserver {
        override fun onFinalDestinationArrival(routeProgress: RouteProgress) {
            PluginUtilities.sendEvent(MapBoxEvents.ON_ARRIVAL)
        }

        override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {

        }

        override fun onWaypointArrival(routeProgress: RouteProgress) {

        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        Log.d("Embedded", "onActivityCreated not implemented")
    }

    override fun onActivityStarted(activity: Activity) {
        Log.d("Embedded", "onActivityStarted not implemented")
    }

    override fun onActivityResumed(activity: Activity) {
        Log.d("Embedded", "onActivityResumed not implemented")
    }

    override fun onActivityPaused(activity: Activity) {
        Log.d("Embedded", "onActivityPaused not implemented")
    }

    override fun onActivityStopped(activity: Activity) {
        Log.d("Embedded", "onActivityStopped not implemented")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        Log.d("Embedded", "onActivitySaveInstanceState not implemented")
    }

    override fun onActivityDestroyed(activity: Activity) {
        Log.d("Embedded", "onActivityDestroyed not implemented")
    }
}