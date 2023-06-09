package com.example.mapviewdemo


import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Environment
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import com.mapbox.geojson.Point
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
import dji.common.camera.SettingsDefinitions
import dji.common.camera.SettingsDefinitions.CameraMode
import dji.common.camera.SettingsDefinitions.FlatCameraMode
import dji.common.error.DJIError
import dji.common.mission.waypoint.*
import dji.common.product.Model
import dji.common.util.CommonCallbacks
import dji.common.util.CommonCallbacks.CompletionCallbackWith
import dji.mop.common.PipelineError.callback
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.flightcontroller.FlightController
import dji.sdk.mission.waypoint.WaypointMissionOperator
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import dji.sdk.products.Aircraft
import dji.sdk.products.HandHeld
import dji.sdk.sdkmanager.DJISDKManager
import io.ticofab.androidgpxparser.parser.GPXParser
import io.ticofab.androidgpxparser.parser.domain.Gpx
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin


class Waypoint1Activity : AppCompatActivity(), MapboxMap.OnMapClickListener, OnMapReadyCallback, View.OnClickListener, TextureView.SurfaceTextureListener{

    private var flightController: FlightController? = null
    //waypoint
    private lateinit var locate: Button
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var config: Button
    private lateinit var upload: Button
    private lateinit var showTrack : Button
    private lateinit var mTextGPS: TextView
//    private lateinit var mTextCMode: TextView
    private lateinit var clearWaypoints : Button
    private lateinit var start_mission: Button
    private lateinit var stop_mission: Button
    private lateinit var force_stop: Button
    private lateinit var startland: Button
    private lateinit var opengpx: Button

    //recording
    private var receivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var codecManager: DJICodecManager? = null //handles the encoding and decoding of video data

    private lateinit var videoSurface: TextureView //Used to display the DJI product's camera video stream
    private lateinit var captureBtn: Button
    private lateinit var mapStyleBtn: Button
//    private lateinit var shootPhotoModeBtn: Button
//    private lateinit var recordVideoModeBtn: Button
    private lateinit var recordBtn: ToggleButton
//    private lateinit var recordingTime: TextView

    companion object {
        const val TAG = "Waypoint1Activity"
        private var waypointMissionBuilder: WaypointMission.Builder? = null // you will use this to add your waypoints

        fun checkGpsCoordination(latitude: Double, longitude: Double): Boolean { // this will check if your gps coordinates are valid
            return latitude > -90 && latitude < 90 && longitude > -180 && longitude < 180 && latitude != 0.0 && longitude != 0.0
        }
    }
    val gpxFolder = "Recordings_DJI/"
    val EARTH_RADIUS_METERS: Double = 6371.0*1000

    private var isAdd = false

    private var stopButtonPressed = false

    private var droneLocationLat: Double = 15.0
    private var droneLocationLng: Double = 15.0
    private var droneLocationAlt: Float = 2f
    private var droneOrientation = 0


    private var droneMarker: Marker? = null
    private val markers: MutableMap<Int, Marker> = ConcurrentHashMap<Int, Marker>()
    private var mapboxMap: MapboxMap? = null

    private var altitude = 2f
    private var speed = 0f
    private var mavicMiniMissionOperator: MavicMiniMissionOperator? = null

    private val waypointList = mutableListOf<Waypoint>()
    private val recordedwaypointList : MutableList<Waypoint> = mutableListOf()
    private var instance: WaypointMissionOperator? = null
    private var finishedAction = WaypointMissionFinishedAction.NO_ACTION
//    private var headingMode = WaypointMissionHeadingMode.AUTO
    private var headingMode = WaypointMissionHeadingMode.USING_WAYPOINT_HEADING

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

        var mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.onCreate(savedInstanceState)
        mapFragment.getMapAsync(this)
        addListener() // will add a listener to the waypoint mission operator

        receivedVideoDataListener = VideoFeeder.VideoDataListener { videoBuffer, size ->
            codecManager?.sendDataToDecoder(videoBuffer, size)
        }

//        getCameraInstance()?.let { camera ->
//            camera.setSystemStateCallback {
//                it.let { systemState ->
//                    //Getting elapsed video recording time in minutes and seconds, then converting into a time string
//                    val recordTime = systemState.currentVideoRecordingTimeInSeconds
//                    val minutes = (recordTime % 3600) / 60
//                    val seconds = recordTime % 60
//                    val timeString = String.format("%02d:%02d", minutes, seconds)
//
//                    //Accessing the UI thread to update the activity's UI
//                    runOnUiThread {
//                        //If the camera is video recording, display the time string on the recordingTime TextView
//                        recordingTime.text = timeString
//                        if (systemState.isRecording) {
//                            recordingTime.visibility = View.VISIBLE
//
//                        } else {
//                            recordingTime.visibility = View.INVISIBLE
//                        }
//                    }
//                }
//            }
//        }
    }


    private fun initUi() {
        locate = findViewById(R.id.locate)
        start = findViewById(R.id.start)
        stop = findViewById(R.id.stop)
        config = findViewById(R.id.config)
        upload = findViewById(R.id.upload)
        mTextGPS = findViewById(R.id.GPSTextView)
//        mTextCMode = findViewById(R.id.CMode)
        showTrack = findViewById(R.id.showTrack)
        clearWaypoints = findViewById(R.id.clearWaypoints)
        start_mission = findViewById(R.id.start_mission)
        stop_mission = findViewById(R.id.stop_mission)
        force_stop = findViewById(R.id.forceStop)
        startland = findViewById(R.id.btn_startland)
        opengpx = findViewById(R.id.btn_opengpx)
        mapStyleBtn = findViewById(R.id.btn_mapstyle)

        locate.setOnClickListener(this)
        start.setOnClickListener(this)
        stop.setOnClickListener(this)
        config.setOnClickListener(this)
        upload.setOnClickListener(this)
        showTrack.setOnClickListener(this)
        clearWaypoints.setOnClickListener(this)
        start_mission.setOnClickListener(this)
        stop_mission.setOnClickListener(this)
        force_stop.setOnClickListener(this)
        startland.setOnClickListener(this)
        opengpx.setOnClickListener(this)
        mapStyleBtn.setOnClickListener(this)

        videoSurface = findViewById(R.id.video_previewer_surface)
//        recordingTime = findViewById(R.id.timer)
        captureBtn = findViewById(R.id.btn_capture)
        recordBtn = findViewById(R.id.btn_record)
//        shootPhotoModeBtn = findViewById(R.id.btn_shoot_photo_mode)
//        recordVideoModeBtn = findViewById(R.id.btn_record_video_mode)

        videoSurface.surfaceTextureListener = this

        captureBtn.setOnClickListener(this)
//        shootPhotoModeBtn.setOnClickListener(this)
//        recordVideoModeBtn.setOnClickListener(this)

//        recordingTime.visibility = View.VISIBLE

        recordBtn.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                startRecord()
            } else {
                stopRecord()
            }
        }
    }


    /* -------------- Waypoint Navigation -------------- */

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

    private fun initFlightController() {
        // this will initialize the flight controller with predetermined data

        // We recommend you use the below settings, a standard american hand style.
        DJIDemoApplication.getFlightController()?.let { flightController ->
            flightController.setStateCallback { flightControllerState ->
                // set the latitude and longitude of the drone based on aircraft location
                droneLocationLat = flightControllerState.aircraftLocation.latitude
                droneLocationLng = flightControllerState.aircraftLocation.longitude
                droneLocationAlt = flightControllerState.aircraftLocation.altitude
                droneOrientation = flightControllerState.getAircraftHeadDirection()
                runOnUiThread {
                    mavicMiniMissionOperator?.droneLocationMutableLiveData?.postValue(flightControllerState.aircraftLocation)
                    updateDroneLocation() // this will be called on the main thread
                }
            }
        }
    }

    private fun updateDroneLocation() { // this will draw the aircraft as it moves
        //Log.i(TAG, "Drone Lat: $droneLocationLat - Drone Lng: $droneLocationLng")
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN()) { return }
        val sb = StringBuffer()
        val pos = LatLng(droneLocationLat, droneLocationLng, droneLocationAlt.toDouble())
        val wayPt = Waypoint(droneLocationLat, droneLocationLng, droneLocationAlt)
        wayPt.heading = droneOrientation
        // the following will draw the aircraft on the screen
        val markerOptions = MarkerOptions()
            .position(pos)
            .icon(IconFactory.getInstance(this).fromResource(R.drawable.aircraft))
        runOnUiThread {
            droneMarker?.remove()
            if (checkGpsCoordination(droneLocationLat, droneLocationLng)) {
                droneMarker = mapboxMap?.addMarker(markerOptions)
                sb.append("Latitude:").append(wayPt.coordinate.latitude).append("\n")
                sb.append("Longitude:").append(wayPt.coordinate.longitude).append("\n")
                sb.append("Altitude:").append(wayPt.altitude).append("\n")
                sb.append("Orientation:").append(wayPt.heading).append("\n")
            }
        }
    }

//    private fun updateCameraMode() { // this will draw the aircraft as it moves
//        //Log.i(TAG, "Drone Lat: $droneLocationLat - Drone Lng: $droneLocationLng")
//        val sbCMode = StringBuffer()
//        val camera = getCameraInstance() ?:return
//        runOnUiThread {
//                camera.getMode(object : CommonCallbacks.CompletionCallbackWith<CameraMode> {
//                    override fun onSuccess(information: CameraMode) {
//                        var value = information
//                        sbCMode.append(value)
//                        mTextCMode.text = sbCMode.toString()
//                    }
//                    override fun onFailure(djiError: DJIError) {
//                    }
//                })
//
//        }
//    }

    private fun recordLocation() {
        Thread {
            while (!stopButtonPressed) {
                runOnUiThread {
                    /*
                    recordedCoordinates.add(
                        LatLng(droneLocationLat,
                            droneLocationLng,
                            droneLocationAlt.toDouble())
                    )*/
                    val newwayPt = Waypoint(droneLocationLat, droneLocationLng, droneLocationAlt)
                    newwayPt.heading = droneOrientation
                    recordedwaypointList.add(newwayPt)
                }
                Thread.sleep(2000)
                setResultToToast(recordedwaypointList.size.toString())
            }
        }.start()
    }

    private fun recordToGPX(points: MutableList<LatLng>){
        val header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx" +
                "  version=\"1.1\"\n" +
                "  creator=\"VinEye - UTCN\"\n" //+
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "  xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
                "  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"\n" +
                "  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n"
        var segments = ""
        val gpxdate = SimpleDateFormat("yyyy-MM-dd")
        val gpxcurrentDate = gpxdate.format(Date()).toString()
        val gpxtime = SimpleDateFormat("hh:mm:ss")
        val gpxcurrentTime = gpxtime.format(Date()).toString()
        for (location in points) {
            segments += "<wpt lat=\"${location.latitude}\" lon=\"${location.longitude}\"><ele>${location.altitude}</ele><time>${gpxcurrentDate}T${gpxcurrentTime}Z</time><desc>0</desc></wpt>\n"
        }
        val footer = "</gpx>"
        val sdf = SimpleDateFormat("yyyy_MM_dd_hh_mm_ss")
        val currentDateandTime = sdf.format(Date()).toString() + ".gpx"
        val mydir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS),
            "$gpxFolder"
        )
        if (!mydir.exists()) {
            mydir.mkdirs()
        }
        val file = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS),
            "$gpxFolder$currentDateandTime"
        )
        try {
            FileOutputStream(file).use {
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

    private fun recordToGPXyaw(points: MutableList<Waypoint>){
        val header = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx\n" +
                "  version=\"1.1\"\n" +
                "  creator=\"VinEye - UTCN - http://www.rocon.utcluj.ro\"\n" +
                "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n" +
                "  xmlns=\"http://www.topografix.com/GPX/1/1\"\n" +
                "  xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd\"\n" +
                "  xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\">\n"
        var segments = ""
        val gpxdate = SimpleDateFormat("yyyy-MM-dd")
        val gpxcurrentDate = gpxdate.format(Date()).toString()
        val gpxtime = SimpleDateFormat("hh:mm:ss")
        val gpxcurrentTime = gpxtime.format(Date()).toString()
        for (location in points) {
            segments += "<wpt lat=\"${location.coordinate.latitude}\" lon=\"${location.coordinate.longitude}\"><ele>${location.altitude}</ele><time>${gpxcurrentDate}T${gpxcurrentTime}Z</time><desc>${location.heading}</desc></wpt>\n"        }
        val footer = "</gpx>"
        val sdf = SimpleDateFormat("yyyy_MM_dd_hh_mm_ss")
        val currentDateandTime = sdf.format(Date()).toString() + "_yaw.gpx"
        val mydir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS),
            "$gpxFolder"
        )
        if (!mydir.exists()) {
            mydir.mkdirs()
        }
        val file = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS),
            "$gpxFolder$currentDateandTime"
        )
        try {
            FileOutputStream(file).use {
                it.write(header.toByteArray())
                it.write(segments.toByteArray())
                it.write(footer.toByteArray())
                it.flush()
                it.close()
                setResultToToast("GPX file saved with yaw")}
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun cleanWaypointList (track: MutableList<LatLng>)
    {
        recordedwaypointList.clear()
        var i = 0
        while (i < track.size -1)
        {
            val distance = acos(sin(track[i].latitude)
                    *sin(track[i+1].latitude)
                    +cos(track[i+1].latitude)*cos(track[i].latitude)
                    *cos(track[i+1].longitude-track[i].longitude))*EARTH_RADIUS_METERS
            if (distance <= 2)
                track.removeAt(i)
            i++
        }
        setResultToToast(track.size.toString())
        waypointMissionBuilder?.waypointList?.clear()
        mapboxMap?.removeAnnotations()
    }

    private fun fromWaypointListToLatLngList (points: MutableList<Waypoint>): MutableList<LatLng> {
        val latlngList = mutableListOf<LatLng>()
        for (point in points)
        {
           latlngList.add(LatLng(point.coordinate.latitude, point.coordinate.longitude, point.altitude.toDouble()))
        }
        return latlngList
    }

    private fun showRecordedWaypoints (points: MutableList<LatLng>){
        for (point in points)
        {
            //val latLng = LatLng(point.latitude(), point.longitude())
            markWaypoint(point)
        }
    }

    private fun createWaypointMission(points: MutableList<Waypoint>): MutableList<Waypoint> {
        if (!points.isNullOrEmpty())
        {
            for (location in points) {
                //val waypoint = Waypoint(location.coordinate.latitude, location.coordinate.longitude, location.altitude)
                if (waypointMissionBuilder == null) {
                    waypointMissionBuilder = WaypointMission.Builder().also { builder ->
                        waypointList.add(location) // add the waypoint to the list
                        builder.waypointList(waypointList).waypointCount(waypointList.size) }
                } else {
                    waypointMissionBuilder?.let { builder ->
                        waypointList.add(location)
                        builder.waypointList(waypointList).waypointCount(waypointList.size) }
                }
            }
            if (waypointMissionBuilder != null)
                setResultToToast("Mission created")
            else
                setResultToToast("Failed to create mission")
        }
        else
            setResultToToast("Failed to create mission")
        return waypointList
    }


    private fun configWayPointMission() {

        speed = 1.0f

        if (waypointMissionBuilder == null) {
            waypointMissionBuilder = WaypointMission.Builder().apply {
                finishedAction(finishedAction)
                headingMode(headingMode)
                autoFlightSpeed(speed)
                maxFlightSpeed(speed)
                flightPathMode(WaypointMissionFlightPathMode.NORMAL)
                gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
                isGimbalPitchRotationEnabled = true
            }
            setResultToToast("Mission builder was null")
        }
        else
        waypointMissionBuilder?.let { builder ->
            builder.apply {
                finishedAction(finishedAction)
                headingMode(headingMode)
                autoFlightSpeed(speed)
                maxFlightSpeed(speed)
                flightPathMode(WaypointMissionFlightPathMode.NORMAL)
                gotoFirstWaypointMode(WaypointMissionGotoWaypointMode.SAFELY)
                isGimbalPitchRotationEnabled = true
            }
            if (builder.waypointList.size > 0) {
                /*var averageAltitude = 0.0f
                for (i in builder.waypointList.indices) { // average altitude
                    averageAltitude += builder.waypointList[i].altitude }
                averageAltitude /= builder.waypointList.size*/
                for (i in builder.waypointList.indices) { // set the altitude of all waypoints to the user defined altitude
//                    builder.waypointList[i].altitude = 2f
//                    builder.waypointList[i].heading = 0
                    builder.waypointList[i].actionRepeatTimes = 1
                    builder.waypointList[i].actionTimeoutInSeconds = 30
                    builder.waypointList[i].turnMode = WaypointTurnMode.CLOCKWISE
                    builder.waypointList[i].addAction(WaypointAction(WaypointActionType.GIMBAL_PITCH, -90))
                    //builder.waypointList[i].addAction(WaypointAction(WaypointActionType.START_TAKE_PHOTO, 0))
                    //builder.waypointList[i].shootPhotoDistanceInterval = 28.956f
                }
//                setResultToToast("Altitude set")
                getWaypointMissionOperator()?.let { operator ->
                    val error = operator.loadMission(builder.build()) // load the mission
                    if (error == null) {
                        setResultToToast("loadWaypoint succeeded")
                    } else {
                        setResultToToast("loadWaypoint failed " + error.description)
                    }
                }
            }
            else
                setResultToToast("loadWaypoint failed, not enough builder")
        }
    }

    private fun uploadWaypointMission() { // upload the mission
        if (getWaypointMissionOperator() == null)
            setResultToToast("waypointmission null")
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

    private fun forceStopWaypointMission() { // stop mission
        getWaypointMissionOperator()?.forceStopMission { error ->
            setResultToToast("Mission Stop: " + if (error == null) "Successfully" else error.description)
        }
    }

    /* ---------------------- Camera Recording --------------------- */
    private fun cameraUpdate() { // update where you're looking on the map
        if (droneLocationLat.isNaN() || droneLocationLng.isNaN())  { return }
        val pos = LatLng(droneLocationLat, droneLocationLng)
        val zoomLevel = 18.0
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(pos, zoomLevel)
        mapboxMap?.moveCamera(cameraUpdate)
    }

    private fun startRecord() {
        val camera = getCameraInstance() ?:return //get camera instance or null if it doesn't exist

        /*
        starts the camera video recording and receives a callback. If the callback returns an error that
        is null, the operation is successful.
        */
        var currentCMode = SettingsDefinitions.FlatCameraMode.UNKNOWN
        camera.getFlatMode(object : CommonCallbacks.CompletionCallbackWith<FlatCameraMode> {
                    override fun onSuccess(information: FlatCameraMode) {
                        currentCMode = information
//                        setResultToToast("Camera Mode $information")
                    }
                    override fun onFailure(djiError: DJIError) {
                    }
                })
        Thread.sleep(50)
//        setResultToToast("Camera Mode $currentCMode")
        if (currentCMode!=SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL){
            camera.setFlatMode(SettingsDefinitions.FlatCameraMode.VIDEO_NORMAL) { error ->
                if (error == null) {
    //                setResultToToast("Switch Camera Mode Succeeded")
                } else {
                    setResultToToast("Switch Camera Error: ${error.description}")
                }
            }
            Thread.sleep(500)
        }

        camera.startRecordVideo {
            if (it == null) {
                setResultToToast("Record Video: Success")
            } else {
                setResultToToast("Record Video Error: ${it.description}")
            }
        }
    }

    //Function to make the DJI product's camera stop video recording
    private fun stopRecord() {
        val camera = getCameraInstance() ?: return //get camera instance or null if it doesn't exist

        /*
        stops the camera video recording and receives a callback. If the callback returns an error that
        is null, the operation is successful.
        */
        camera.stopRecordVideo {
            if (it == null) {
                setResultToToast("Stop Recording: Success")
            } else {
                setResultToToast("Stop Recording: Error ${it.description}")
            }
        }
    }

    private fun captureAction() {
        val camera = getCameraInstance() ?:return //get camera instance or null if it doesn't exist

        /*
        starts the camera video recording and receives a callback. If the callback returns an error that
        is null, the operation is successful.
        */
        var currentCMode = SettingsDefinitions.FlatCameraMode.UNKNOWN
        camera.getFlatMode(object : CommonCallbacks.CompletionCallbackWith<FlatCameraMode> {
            override fun onSuccess(information: FlatCameraMode) {
                currentCMode = information
//                        setResultToToast("Camera Mode $information")
            }
            override fun onFailure(djiError: DJIError) {
            }
        })
        Thread.sleep(50)
        if(currentCMode!=SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE){
            camera.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE) { error ->
                if (error == null) {
//                setResultToToast("Switch Camera Mode Succeeded")
                } else {
                    setResultToToast("Switch Camera Error: ${error.description}")
                }
            }
            Thread.sleep(500)
        }

        camera.startShootPhoto {
            if (it == null) {
                setResultToToast("Photo taken.")
            } else {
                setResultToToast("Photo Capture Error: ${it.description}")
            }
        }
//        camera.stopShootPhoto {
//            if (it == null) {
////                setResultToToast("Photo Capture Stop: Success")
//            } else {
//                setResultToToast("Photo Capture Stop Error: ${it.description}")
//            }
//        }
    }

    //Function that initializes the display for the videoSurface TextureView
    private fun initPreviewer() {

        //gets an instance of the connected DJI product (null if nonexistent)
        val product: BaseProduct = getProductInstance() ?: return

        //if DJI product is disconnected, alert the user
        if (!product.isConnected) {
            setResultToToast(getString(R.string.disconnected))
        } else {
            /*
            if the DJI product is connected and the aircraft model is not unknown, add the
            receivedVideoDataListener to the primary video feed.
            */
            videoSurface.surfaceTextureListener = this
            if (product.model != Model.UNKNOWN_AIRCRAFT) {
                receivedVideoDataListener?.let {
                    VideoFeeder.getInstance().primaryVideoFeed.addVideoDataListener(it)
                }
//                runOnUiThread {
//                    updateCameraMode()
//                }
            }
        }
    }

    private fun uninitPreviewer() {
        val camera: Camera = getCameraInstance() ?: return
    }

    //When a TextureView's SurfaceTexture is ready for use, use it to initialize the codecManager
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        if (codecManager == null) {
            codecManager = DJICodecManager(this, surface, width, height)
        }
    }

    //when a SurfaceTexture's size changes...
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    //when a SurfaceTexture is about to be destroyed, uninitialize the codedManager
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        codecManager?.cleanSurface()
        codecManager = null
        return false
    }

    //When a SurfaceTexture is updated...
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 111 && resultCode == RESULT_OK) {
            val selectedFile = data?.data // The URI with the location of the file
            var gpxfile = File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS),
                "$gpxFolder"
            )
            if (selectedFile != null) {
                val filename = selectedFile.path?.substring(selectedFile.path!!.lastIndexOf('/') + 1)
                gpxfile = File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS),
                    "$gpxFolder$filename"
                )
            }
            val parser = GPXParser() // consider injection
            try {
                val inputStream: InputStream = File(gpxfile.path).inputStream()
                val parsedGpx: Gpx? = parser.parse(inputStream) // consider using a background thread
                recordedwaypointList.clear()
                parsedGpx?.let {
                    // do something with the parsed track
                    // see included example app and tests
                    var readwaypointList =it.wayPoints
                    for (i in readwaypointList.indices){
                        val wp =  readwaypointList.get(i)
                        val newwayPt = Waypoint(wp.latitude, wp.longitude, wp.elevation.toFloat())
                        newwayPt.heading = wp.desc.toInt()
                        recordedwaypointList.add(newwayPt)
                    }
                } ?: {
                    setResultToToast("Error at parsing")
                }
            } catch (e: IOException) {
                // do something with this exception
                e.message?.let { setResultToToast(it) }
                e.printStackTrace()
            } catch (e: XmlPullParserException) {
                // do something with this exception
                e.message?.let { setResultToToast(it) }
                e.printStackTrace()
            }
        }
    }

    private fun changeMapStyle(){
        val style_street = Style.MAPBOX_STREETS
        val style_sat = Style.SATELLITE
//                val style_string_sat = "mapbox://styles/mapbox/satellite-v9"
//                val style_string_street = "mapbox://styles/mapbox/streets-v11"
        val style_string_sat = "sat"
        val style_string_street = "street"
        var style = mapboxMap?.getStyle()?.uri
//                if (style.toString() == style_string_sat){
        if (style.toString().contains(style_string_sat)){
            setResultToToast("Changing style to street")
            mapboxMap?.setStyle(style_street) { // set the view of the map
            }
        }
        else{
//                    setResultToToast(style.toString())
            setResultToToast("Changing style to satellite")
            mapboxMap?.setStyle(style_sat) { // set the view of the map
            }
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
                //initializeWaypointList()

            }
            R.id.stop -> {
                if (!stopButtonPressed) stopButtonPressed = true
                //showRecordedWaypoints(recordedCoordinates)
                //val copyingStrignBufferGPS = stringBufferGPS
                //saveLogFile(copyingStrignBufferGPS)
                //stringBufferGPS = StringBuffer()
//                recordToGPX(fromWaypointListToLatLngList(recordedwaypointList))
                recordToGPXyaw(recordedwaypointList)
                //mutableGeoJson = mutableListOf()
                setResultToToast("Route coordinates:" + recordedwaypointList.size.toString())
            }
            R.id.showTrack -> {
                //setResultToToast(recordedCoordinates.size.toString())
                //showTrack(routeCoordinates)
                createWaypointMission(recordedwaypointList)
                showRecordedWaypoints(fromWaypointListToLatLngList(recordedwaypointList))
                cameraUpdate()
            }
            R.id.clearWaypoints -> {
                cleanWaypointList(recordedCoordinates)
            }

            R.id.config -> {
                configWayPointMission()
            }

            R.id.upload -> {
                uploadWaypointMission()
            }
            R.id.start_mission-> {
                startWaypointMission()
            }

            R.id.stop_mission -> {
                stopWaypointMission()
            }

            R.id.forceStop -> {
                forceStopWaypointMission()
            }
            R.id.btn_startland -> {
                DJIDemoApplication.getFlightController()?.let { controller ->
                    controller.startLanding { djiError ->
                        if (djiError != null) {
                            setResultToToast("Landing Error: ${djiError.description}")
                        } else {
                            setResultToToast("Start Landing Success")
                        }
                    }
                }
            }
            R.id.btn_opengpx -> {
                val intent = Intent()
                    .setType("*/*")
                    .setAction(Intent.ACTION_GET_CONTENT)
                startActivityForResult(Intent.createChooser(intent, "Select a file"), 111)
            }
            R.id.btn_mapstyle -> {
                changeMapStyle()
            }
            R.id.btn_capture -> {
                captureAction()
            }
        }
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


    private fun getProductInstance(): BaseProduct? {
        return DJISDKManager.getInstance().product
    }

    /*
    Function used to get an instance of the camera in use from the DJI product
    */
    private fun getCameraInstance(): Camera? {
        if (getProductInstance() == null) return null

        return when {
            getProductInstance() is Aircraft -> {
                (getProductInstance() as Aircraft).camera
            }
            getProductInstance() is HandHeld -> {
                (getProductInstance() as HandHeld).camera
            }
            else -> null
        }
    }

    //Function that returns True if a DJI aircraft is connected
    private fun isAircraftConnected(): Boolean {
        return getProductInstance() != null && getProductInstance() is Aircraft
    }

    //Function that returns True if a DJI product is connected
    private fun isProductModuleAvailable(): Boolean {
        return (getProductInstance() != null)
    }

    //Function that returns True if a DJI product's camera is available
    private fun isCameraModuleAvailable(): Boolean {
        return isProductModuleAvailable() && (getProductInstance()?.camera != null)
    }

    //Function that returns True if a DJI camera's playback feature is available
    private fun isPlaybackAvailable(): Boolean {
        return isCameraModuleAvailable() && (getProductInstance()?.camera?.playbackManager != null)
    }


    override fun onResume() {
        super.onResume()
        initFlightController()
        initPreviewer()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListener()
        uninitPreviewer()
    }

    private fun addListener() {
        getWaypointMissionOperator()?.addListener(eventNotificationListener)
    }

    private fun removeListener() {
        getWaypointMissionOperator()?.removeListener()
    }

    override fun onPause() {
        uninitPreviewer()
        super.onPause()
    }


}