package com.example.mapviewdemo


import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.geojson.*
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.IconFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.maps.SupportMapFragment
import com.mapbox.mapboxsdk.style.layers.*
import com.mapbox.mapboxsdk.style.layers.Property.VISIBLE
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import dji.common.error.DJIError
import dji.common.mission.waypoint.*
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin


class Waypoint1Activity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback, View.OnClickListener {

    private lateinit var locate: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var showTrack : Button
    private lateinit var mTextGPS: TextView


    companion object {
        const val TAG = "Waypoint1Activity"
        private var waypointMissionBuilder: WaypointMission.Builder? = null // you will use this to add your waypoints

        fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean { // this will check if your gps coordinates are valid
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }

    val EARTH_RADIUS_METERS: Double = 6371.0*1000

    private var isAdd = false

    private var stopButtonPressed = false

    private var droneLocationLat: Double = 15.0
    private var droneLocationLng: Double = 15.0
    private var droneLocationAlt: Float = 10f


    private var droneMarker: Marker? = null
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()
    private var mapboxMap: MapboxMap? = null

    private var altitude = 100f
    private var speed = 10f
    private var mavicMiniMissionOperator: MavicMiniMissionOperator? = null

    private val waypointList = mutableListOf<Waypoint>()
    private var instance: WaypointMissionOperator? = null
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
    private var headingMode = WaypointMissionHeadingMode.AUTO

    //private var stringBufferGPS = StringBuffer()
    //private lateinit var mutableGPSList : MutableList<LatLng>
    private var mutableGeoJson : MutableList<Point> = mutableListOf()
    private var routeCoordinates : MutableList<Point> = mutableListOf()
    private var recordedCoordinates: MutableList<LatLng> = mutableListOf()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this, getString(R.string.mapbox_access_token)) // this will get your mapbox instance using your access token
        setContentView(R.layout.activity_waypoint1) // use the waypoint1 activity layout

        initUi() // initialize the UI

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)
        addListener() // will add a listener to the waypoint mission operator
    }

    override fun onMapReady(mapboxMap: MapboxMap) {
        this.mapboxMap = mapboxMap // initialize the map
        mapboxMap.addOnMapClickListener(this)
        mapboxMap.setStyle(Style.MAPBOX_STREETS) { // set the view of the map
        }
    }


    override fun onMapClick(point: LatLng): Boolean {
        if (isAdd) { // if the user is adding waypoints
            markWaypoint(point) // this will mark the waypoint visually
            val waypoint = Waypoint(point.latitude, point.longitude, point.altitude.toFloat()) // this will create the waypoint object to be added to the mission

            if (waypointMissionBuilder == null){
                waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                    waypointList.add(waypoint) // add the waypoint to the list
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            } else {
                waypointMissionBuilder?.let { builder ->
                    waypointList.add(waypoint)
                    builder.waypointList(waypointList).waypointCount(waypointList.size)
                }
            }
        } else {
            setResultToToast("Cannot Add Waypoint")
        }
        return true
    }

    private fun markWaypoint(point: LatLng) {
        val markerOptions = MarkerOptions()
            .position(point)
        mapboxMap?.let {
            val marker = it.addMarker(markerOptions)
            markers.put(markers.size, marker)
        }
    }

    override fun onResume() {
        super.onResume()
        initFlightController()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListener()
    }

    private fun addListener() {
        getWaypointMissionOperator()?.addListener(eventNotificationListener)
    }

    private fun removeListener() {
        getWaypointMissionOperator()?.removeListener()
    }

    private fun initUi() {
        locate = findViewById(R.id.locate)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        mTextGPS = findViewById(R.id.GPSTextView)
        showTrack = findViewById(R.id.showTrack)

        locate.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
        showTrack.setOnClickListener(this)
    }



    private fun initFlightController() {
        // this will initialize the flight controller with predetermined data
        DJIDemoApplication.getFlightController()?.let { flightController ->
            flightController.setStateCallback { flightControllerState ->
                // set the latitude and longitude of the drone based on aircraft location
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                droneLocationAlt = flightControllerState.aircraftLocation.altitude
                runOnUiThread {
                    mavicMiniMissionOperator?.droneLocationMutableLiveData?.postValue(flightControllerState.aircraftLocation)
                    updateDroneLocation() // this will be called on the main thread
                }
            }
        }
    }


    private fun updateDroneLocation() { // this will draw the aircraft as it moves
        //Log.i(TAG, "Drone Lat: $droneLocationLat - Drone Lng: $droneLocationLng")
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN())  { return }
        val sb = StringBuffer()
        val pos = LatLng(droneLocationLat, droneLocationLng)
        // the following will draw the aircraft on the screen
        val markerOptions = MarkerOptions()
            .position(pos)
            .icon(IconFactory.getInstance(this).fromResource(R.drawable.aircraft))
        runOnUiThread {
            droneMarker?.remove()
            if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                droneMarker = mapboxMap?.addMarker(markerOptions)
                sb.append("Latitude:").append(pos.latitude).append("\n")
                sb.append("Longitude:").append(pos.longitude).append("\n")
                sb.append("Altitude:").append(pos.altitude).append("\n")
                mTextGPS.text = sb.toString()
            }
        }
    }

    private fun recordLocation() {
        /* Thread {
            while (!stopButtonPressed) {
            runOnUiThread {
                stringBufferGPS
                    .append(droneLocationLng)
                    .append(",")
                    .append(droneLocationLat)
                    .append(",")
                    .append(droneLocationAlt)
                    .append(System.lineSeparator())}
                Thread.sleep(2000)
                setResultToToast(stringBufferGPS.length.toString())
            }
        }.start()*/

       /* Thread {
            while (!stopButtonPressed) {
                runOnUiThread {
                    mutableGeoJson.add(Point.fromLngLat(droneLocationLat, droneLocationLat))}
                Thread.sleep(2000)
                setResultToToast(mutableGPSList.size.toString())
            }
        }.start()*/
        /*Thread {
            while (!stopButtonPressed) {
                runOnUiThread {
                    routeCoordinates.add(Point.fromLngLat(droneLocationLng, droneLocationLat))}
                Thread.sleep(2000)
                setResultToToast(routeCoordinates.size.toString())
            }
        }.start()*/
        Thread {
            while (!stopButtonPressed) {
                runOnUiThread {
                    recordedCoordinates.add(
                        LatLng( droneLocationLat,
                                droneLocationLng,
                                droneLocationAlt.toDouble()
                        )
                    )
                }
                Thread.sleep(2000)
                setResultToToast(recordedCoordinates.size.toString())
            }
        }.start()
    }


    private fun recordToGFX(points: MutableList<LatLng>){
        val header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx\n" +
                "  version=\"1.1\"\n" +
                "  creator=\"Runkeeper - http://www.runkeeper.com\"\n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "  xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
                "  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"\n" +
                "  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n"
        var segments = ""
        for (location in points) {
            segments += "<wpt lat=\"${location.latitude}\" lon=\"${location.longitude}\"></wpt>\n"
        }
        val footer = "</gpx>"
        val sdf = SimpleDateFormat("yyyy_MM_dd_hh_mm_ss")
        val currentDateandTime = sdf.format(Date()).toString() + ".gpx"
        val mydir: File =
            this.getDir("Recordings_DJI_ez", MODE_PRIVATE) // name:app_Recordings_DJI_ez
        if (!mydir.exists()) {
            mydir.mkdirs()
        }
        val fileName = File(mydir, currentDateandTime)
        try {
                FileOutputStream(fileName).use {
                it.write(header.toByteArray())
                it.write(segments.toByteArray())
                it.write(footer.toByteArray())
                it.flush()
                it.close()
                setResultToToast("GPX file saved")}

        } catch (e: IOException) {
            e.printStackTrace()
        }

    }

    private fun recordtoGEOJson (points: MutableList<Point>)
    {
        var listofFeatures = mutableListOf<Feature>()
        for (location in points)
        {
            listofFeatures.add(Feature.fromGeometry(location))
        }
        var featureCollection : FeatureCollection = FeatureCollection.fromFeatures(listofFeatures)

        val sdf = SimpleDateFormat("yyyy_MM_dd_hh_mm_ss")
        val currentDateandTime = sdf.format(Date()).toString() + ".json"
        val mydir: File =
            this.getDir("Recordings_GEOJson_DJI_ez", MODE_PRIVATE) // name:app_Recordings_DJI_ez
        if (!mydir.exists()) {
            mydir.mkdirs()
        }
        val fileName = File(mydir, currentDateandTime)
        try {
            FileOutputStream(fileName).use {
                it.write(featureCollection.toJson().toByteArray())
                setResultToToast(recordedCoordinates.toString())
            }
        } catch (e:IOException) {
            e.printStackTrace()
        }
    }


    private fun showTrack(coordinatesList : MutableList<Point>) {
        mapboxMap?.getStyle { style ->
            style.addSource(GeoJsonSource(
                "line-source",
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(
                        LineString.fromLngLats(coordinatesList)))
            )
                //VectorSource("museums_source", "mapbox://mapbox.2opop9hr")
            )

            val trackLayer = LineLayer("track_line", "line-source")
            trackLayer.sourceLayer = "line-source"
            trackLayer.setProperties(
                visibility(VISIBLE),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineWidth(5f),
                lineColor(Color.parseColor("#e55e5e")),
            )
            style.addLayer(trackLayer)
        }
    }

    private fun showWater() {
        mapboxMap?.getStyle {
            val waterLayer = it.getLayer("water")
            waterLayer?.setProperties(PropertyFactory.fillColor(Color.parseColor("#004f6b")))
        }
    }


    override fun onClick(v: View?) {
        when (v?.id) {
            R.id.locate -> { // will draw the drone and move camera to the position of the drone on the map
                updateDroneLocation()
                cameraUpdate()
                setResultToToast("$droneLocationLat update $droneLocationLng")
            }
            R.id.start -> {
                //coordinateList.add("recording started")
                //start.isPressed = true
                if(stopButtonPressed) stopButtonPressed = false
                recordLocation()

            }
            R.id.stop -> {
                if (!stopButtonPressed) stopButtonPressed = true
                //showRecordedWaypoints(recordedCoordinates)
                //val copyingStrignBufferGPS = stringBufferGPS
                //saveLogFile(copyingStrignBufferGPS)
                //stringBufferGPS = StringBuffer()
                //recordtoGEOJson(routeCoordinates)
                //mutableGeoJson = mutableListOf()
                setResultToToast("Recording stopped, array size:" + recordedCoordinates.size.toString())
            }
            R.id.showTrack -> {
                //setResultToToast(recordedCoordinates.size.toString())
                //showTrack(routeCoordinates)
                showRecordedWaypoints(recordedCoordinates)
            }
        }
    }

    private fun cleanWaypointList (track: MutableList<Waypoint>)
    {
        for (i in 1 until track.size){
        /*var i = 0
        while (i < track.size -1)*/
            val distance = acos(sin(track[i].coordinate.latitude)
                                *sin(track[i+1].coordinate.latitude)
                                +cos(track[i+1].coordinate.latitude)*cos(track[i].coordinate.latitude)
                                *cos(track[i+1].coordinate.longitude-track[i].coordinate.longitude))*EARTH_RADIUS_METERS
            if (distance <= 2)
                track.removeAt(i)
        }

    }

    private fun showRecordedWaypoints (points: MutableList<LatLng>){
        for (point in points)
        {
            //val latLng = LatLng(point.latitude(), point.longitude())
            markWaypoint(point)
        }
    }

    private fun fromListToWaypoints(points: MutableList<LatLng>) {
        if (points.isNotEmpty())
        {
            for (location in points) {
                val waypoint = Waypoint(location.latitude, location.longitude, location.altitude.toFloat())
                if (waypointMissionBuilder == null) {
                    waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                        waypointList.add(waypoint) // add the waypoint to the list
                        builder.waypointList(waypointList).waypointCount(waypointList.size) }
                } else {
                    waypointMissionBuilder?.let { builder ->
                        waypointList.add(waypoint)
                        builder.waypointList(waypointList).waypointCount(waypointList.size) }
                }
            }
        }
    }

    private fun clearWaypoints(){
        waypointMissionBuilder?.waypointList?.clear()
    }

    private fun uploadWaypointMission() { // upload the mission
        getWaypointMissionOperator()!!.uploadMission { error ->
            if (error == null) {
                setResultToToast("Mission upload successfully!")
            } else {
                setResultToToast("Mission upload failed")
            }
        }
    }

    private fun startWaypointMission() { // start mission
        getWaypointMissionOperator()?.startMission { error ->
            setResultToToast("Mission Start: " + if (error == null) "Successfully" else error.description)
        }
    }

    private fun stopWaypointMission() { // stop mission
        getWaypointMissionOperator()?.stopMission { error ->
            setResultToToast("Mission Stop: " + if (error == null) "Successfully" else error.description)
        }
    }



    private fun cameraUpdate() { // update where you're looking on the map
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN())  { return }
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        mapboxMap?.moveCamera(cameraUpdate)
    }

    private fun setResultToToast(string: String) {
        runOnUiThread { Toast.makeText(this, string, Toast.LENGTH_SHORT).show() }
    }

    private val eventNotificationListener: WaypointMissionOperatorListener = object : WaypointMissionOperatorListener {
        override fun onDownloadUpdate(downloadEvent: WaypointMissionDownloadEvent) {}
        override fun onUploadUpdate(uploadEvent: WaypointMissionUploadEvent) {}
        override fun onExecutionUpdate(executionEvent: WaypointMissionExecutionEvent) {}
        override fun onExecutionStart() {}
        override fun onExecutionFinish(error: DJIError?) {
            setResultToToast("Execution finished: " + if (error == null) "Success!" else error.description)
        }
    }


    private fun getWaypointMissionOperator(): MavicMiniMissionOperator? { // returns the mission operator
        if(mavicMiniMissionOperator == null){
            mavicMiniMissionOperator = MavicMiniMissionOperator(this)
        }
        return mavicMiniMissionOperator
    }
}