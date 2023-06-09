package com.example.mapviewdemo
import android.annotation.SuppressLint
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.example.mapviewdemo.DJIDemoApplication.getCameraInstance
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.error.DJIMissionError
import dji.common.flightcontroller.ControlMode
import dji.common.flightcontroller.LocationCoordinate3D
import dji.common.flightcontroller.virtualstick.*
import dji.common.gimbal.Rotation
import dji.common.gimbal.RotationMode
import dji.common.mission.MissionState
import dji.common.mission.waypoint.Waypoint
import dji.common.mission.waypoint.WaypointMission
import dji.common.mission.waypoint.WaypointMissionState
import dji.common.model.LocationCoordinate2D
import dji.common.util.CommonCallbacks
import dji.sdk.camera.Camera
import dji.sdk.mission.waypoint.WaypointMissionOperatorListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*


class MavicMiniMissionOperator(context: Context) {
    private var altitudeHack: Boolean = false
    private var isLanding: Boolean = false
    private var isLanded: Boolean = false
    private var isAirborne: Boolean = false
    private var photoIsSuccess: Boolean = false
    private var observeGimbal: Boolean = false
    private val activity: AppCompatActivity
    private val mContext = context
    private var gimbalObserver: Observer<Float>? = null

    private var state: MissionState = WaypointMissionState.INITIAL_PHASE
    private lateinit var mission: WaypointMission
    private lateinit var waypoints: MutableList<Waypoint>
    private lateinit var currentWaypoint: Waypoint

    private var operatorListener: WaypointMissionOperatorListener? = null
    var droneLocationMutableLiveData: MutableLiveData<LocationCoordinate3D> =
        MutableLiveData()
    val droneLocationLiveData: LiveData<LocationCoordinate3D> = droneLocationMutableLiveData

    private var travelledLongitude = false
    private var travelledLatitude = false
    private var travelledAltitude = false
    private var waypointTracker = 0

    private var sendDataTimer =
        Timer() //used to schedule tasks for future execution in a background thread
    private lateinit var sendDataTask: SendDataTask

    private var originalLongitudeDiff = -1.0
    private var originalLatitudeDiff = -1.0
    private var originalAltitudeDiff = -1.0
    private var originalAltitudeDeadLock_start = System.currentTimeMillis()
    private var originalAltitudeDeadLock = false
    private var originalAltitudeDeadLock_timeout = 5*1000
    private var directions = Direction(altitude = 0f)
    private var altitudeDeadLockHeight = 0.0

    private var currentGimbalPitch: Float = 0f
    private var gimbalPitchLiveData: MutableLiveData<Float> = MutableLiveData()

    private var distanceToWaypoint = 0.0
    private var distanceToWaypoint3D = 0.0
    private var photoTakenToggle = false
    companion object {
        const val TAG = "MMMissionOperator"
    }

    init {
        initFlightController()
        initGimbalListener()
        activity = context as AppCompatActivity
    }



    private fun initFlightController() {
        Log.d(TAG,"Initialize Flight Controller")
        DJIDemoApplication.getFlightController()?.let { flightController ->
            flightController.setVirtualStickModeEnabled(
                true,
                null
            )//enables the aircraft to be controlled virtually

            //setting the modes for controlling the drone's roll, pitch, and yaw
            flightController.rollPitchControlMode = RollPitchControlMode.VELOCITY
            flightController.yawControlMode = YawControlMode.ANGLE
            flightController.verticalControlMode = VerticalControlMode.POSITION

            //setting the drone's flight coordinate system
            flightController.rollPitchCoordinateSystem = FlightCoordinateSystem.GROUND
        }
    }

    private fun initGimbalListener() {
        Log.d(TAG,"Initializing Gimbal Listener")
        DJIDemoApplication.getGimbal()?.setStateCallback { gimbalState ->
            currentGimbalPitch = gimbalState.attitudeInDegrees.pitch
            gimbalPitchLiveData.postValue(currentGimbalPitch)
        }
    }

    //Function for taking a a single photo using the DJI Product's camera
    private fun takePhoto(): Boolean {
        val camera: Camera = getCameraInstance() ?: return false

        // Setting the camera capture mode to SINGLE, and then taking a photo using the camera.
        // If the resulting callback for each operation returns an error that is null, then the two operations are successful.
        val photoMode = SettingsDefinitions.ShootPhotoMode.SINGLE

//        pauseMission()

        camera.setShootPhotoMode(photoMode) { djiError ->
            if (djiError == null) {
                camera.startShootPhoto { djiErrorSecond ->
                    if (djiErrorSecond == null) {
                        Log.d(TAG, "take photo: success")
                        showToast(mContext, "take photo: success")
                        this.state = WaypointMissionState.EXECUTING
                        this.photoIsSuccess = true
                    } else {
                        Log.d(TAG, "Take Photo Failure: ${djiError?.description}")
                        this.state = WaypointMissionState.EXECUTION_PAUSED
                        this.photoIsSuccess = false
                    }
                }
            }
        }
        return this.photoIsSuccess
    }


    //Function used to set the current waypoint mission and waypoint list
    fun loadMission(mission: WaypointMission?): DJIError? {
        Log.d(TAG,"loading mission")
        return if (mission == null) {
            this.state = WaypointMissionState.NOT_READY
            DJIMissionError.NULL_MISSION
        } else {
            this.mission = mission
            this.waypoints = mission.waypointList
            this.state = WaypointMissionState.READY_TO_UPLOAD
            null
        }
    }

    private fun pauseMission(){
        if (this.state == WaypointMissionState.EXECUTING){
            Log.d(TAG, "trying to pause")
//            WaypointMissionOperator().pauseMission {
//            }
            this.state = WaypointMissionState.EXECUTION_PAUSED
        }
    }

    //Function used to get the current waypoint mission ready to start
    fun uploadMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        Log.d(TAG,"uploading mission")
        if (this.state == WaypointMissionState.READY_TO_UPLOAD) {
            this.state = WaypointMissionState.READY_TO_START
            callback?.onResult(null)
        } else {
            this.state = WaypointMissionState.NOT_READY
            callback?.onResult(DJIMissionError.UPLOADING_WAYPOINT)
        }
    }

    //Function used to make the drone takeoff and then begin execution of the current waypoint mission
    fun startMission(angle:Float,callback: CommonCallbacks.CompletionCallback<DJIError>?) {
        gimbalObserver = Observer { gimbalPitch: Float ->
            if (gimbalPitch == angle && !isAirborne) {
                isAirborne = true
                showToast(mContext, "Starting to Takeoff")
                Log.d(TAG, "startMission: Start take off")
                DJIDemoApplication.getFlightController()?.startTakeoff { error ->
                    if (error == null) {
                        callback?.onResult(null)
                        this.state = WaypointMissionState.READY_TO_EXECUTE

//                        getCameraInstance()?.setFlatMode(SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE) { error ->
//                            if (error == null) {
//                                showToast(mContext, "Switch Camera Mode Succeeded")
//                            } else {
//                                showToast(mContext, "Switch Camera Error: ${error.description}")
//                            }
//                        }

                        val handler = Handler(Looper.getMainLooper())
                        handler.postDelayed({
                            executeMission()
                        }, 8000)
                    } else {
                        callback?.onResult(error)
                    }
                }
            }
        }
        if (this.state == WaypointMissionState.READY_TO_START) {
//            rotateGimbalDown()
            rotateGimbal(angle)
            gimbalObserver?.let {
                gimbalPitchLiveData.observe(activity, it)
            }
        } else {
            callback?.onResult(DJIMissionError.FAILED)
        }
    }

    private fun rotateGimbalDown() {
        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(-90f).build()
        try {
            val gimbal = DJIDemoApplication.getGimbal()

            gimbal?.rotate(
                rotation
            ) { djiError ->
                if (djiError == null) {
                    Log.d(TAG, "rotate gimbal success")
                    showToast(mContext, "rotate gimbal success")

                } else {
                    Log.d(TAG, "rotate gimbal error " + djiError.description)
                    showToast(mContext, djiError.description)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Drone is likely not connected")
        }
    }

    fun rotateGimbal(angle: Float) {
        val rotation = Rotation.Builder().mode(RotationMode.ABSOLUTE_ANGLE).pitch(angle).build()
        try {
            val gimbal = DJIDemoApplication.getGimbal()

            gimbal?.rotate(
                rotation
            ) { djiError ->
                if (djiError == null) {
                    Log.d(TAG, "rotate gimbal success")
//                    showToast(mContext, "rotate gimbal success")

                } else {
                    Log.d(TAG, "rotate gimbal error " + djiError.description)
                    showToast(mContext, djiError.description)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Drone is likely not connected")
        }
    }

    /*
 * Calculate the euclidean distance between two points.
 * Ignore curvature of the earth.
 *
 * @param a: The first point
 * @param b: The second point
 * @return: The square of the distance between a and b
 */
    private fun distanceInMeters(a: LocationCoordinate2D, b: LocationCoordinate2D): Double {
        var distance2D = sqrt((a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)) * 111139.0
        Log.d(TAG,"2D distance is ${distance2D.toString()}")
        return distance2D
    }

    private fun distanceInMeters3d(a: LocationCoordinate2D,alt_a: Float, b: LocationCoordinate2D,alt_b: Float): Double {
        var distance2D = sqrt((a.longitude - b.longitude).pow(2.0) + (a.latitude - b.latitude).pow(2.0)) * 111139.0
        Log.d(TAG,"2D distance is ${distance2D.toString()}")
        var distance3D = sqrt((alt_a.toDouble() - alt_b.toDouble()).pow(2.0) + distance2D.pow(2.0))
        Log.d(TAG,"3D distance is ${distance3D.toString()}")
        return  distance3D
    }

//    private fun distanceInMeters3d(
//        a: LocationCoordinate2D,
//        alt_a: Float,
//        b: LocationCoordinate2D,
//        alt_b: Float
//    ): Double {
//        //from https://stackoverflow.com/a/19412565
//        val R = 6367444.65
//        val dlon = Math.toRadians(a.longitude) - Math.toRadians(b.longitude)
//        val dlat = Math.toRadians(a.latitude) - Math.toRadians(b.latitude)
//
//        val a =
//            sin(dlat / 2).pow(2.0) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(
//                dlon / 2
//            ).pow(2.0)
//        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
//
//        var distance2D = R * c
//        return sqrt((alt_a.toDouble() - alt_b.toDouble()).pow(2.0) + distance2D.pow(2.0))
//    }

    //Function used to execute the current waypoint mission
    private fun executeMission() {
        Log.d(TAG,"executing mission")
        state = WaypointMissionState.EXECUTION_STARTING
        operatorListener?.onExecutionStart()
        //running the execution in a coroutine to prevent blocking the main thread
        activity.lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                if (waypointTracker >= waypoints.size) return@withContext
                currentWaypoint = waypoints[waypointTracker] //getting the current waypoint
                droneLocationLiveData.observe(activity, locationObserver)
                showToast(mContext, currentWaypoint.altitude.toString())
            }
        }
    }

    private val locationObserver = Observer { currentLocation: LocationCoordinate3D ->
        //observing changes to the drone's location coordinates
        state = WaypointMissionState.EXECUTING
        distanceToWaypoint3D = distanceInMeters3d(
            LocationCoordinate2D(
                currentWaypoint.coordinate.latitude,
                currentWaypoint.coordinate.longitude,
            ),currentWaypoint.altitude,
            LocationCoordinate2D(
                currentLocation.latitude,
                currentLocation.longitude
            ),currentLocation.altitude
        )
//        distanceToWaypoint = distanceInMeters(
//            LocationCoordinate2D(
//                currentWaypoint.coordinate.latitude,
//                currentWaypoint.coordinate.longitude,
//            ),
//            LocationCoordinate2D(
//                currentLocation.latitude,
//                currentLocation.longitude
//            )
//        )

        distanceToWaypoint = distanceToWaypoint3D
//        if (!isLanded && !isLanding) {
            //If the drone has arrived at the destination, take a photo.
//            if (!photoTakenToggle && (distanceToWaypoint < 1.5)) {//if you haven't taken a photo
//                photoTakenToggle = takePhoto()
//                Log.d(
//                    TAG,
//                    "attempting to take photo: $photoTakenToggle, $photoIsSuccess"
//                )
//            } else if (photoTakenToggle && (distanceToWaypoint >= 1.5)) {
//                photoTakenToggle = false
//                photoIsSuccess = false
//            }
//        }

        val longitudeDiff =
            currentWaypoint.coordinate.longitude - currentLocation.longitude
        val latitudeDiff =
            currentWaypoint.coordinate.latitude - currentLocation.latitude
        val altitudeDiff = currentWaypoint.altitude.toDouble() - currentLocation.altitude.toDouble()
        Log.d(TAG,"Distances: Lat: ${abs(latitudeDiff).toString()} Lon: ${abs(longitudeDiff).toString()}  Alt: ${abs(altitudeDiff).toString()}")
        if (abs(latitudeDiff) > originalLatitudeDiff) {
            originalLatitudeDiff = abs(latitudeDiff)
        }
        if (abs(longitudeDiff) > originalLongitudeDiff) {
            originalLongitudeDiff = abs(longitudeDiff)
        }
        if (abs(altitudeDiff) > originalAltitudeDiff) {
            originalAltitudeDiff = abs(altitudeDiff)
        }
        //terminating the sendDataTimer and creating a new one
        sendDataTimer.cancel()
        sendDataTimer = Timer()

        if (!travelledLongitude) {//!travelledLongitude
            val speed = kotlin.math.max(
                (mission.autoFlightSpeed * (abs(longitudeDiff) / (originalLongitudeDiff))).toFloat(),
                0.5f
            )
            directions.pitch = if (longitudeDiff > 0) speed else -speed
        }
        if (!travelledLatitude) {
            val speed = kotlin.math.max(
                (mission.autoFlightSpeed * (abs(latitudeDiff) / (originalLatitudeDiff))).toFloat(),
                0.5f
            )
            directions.roll = if (latitudeDiff > 0) speed else -speed
        }
//        if (!travelledAltitude) {
//            val speed = kotlin.math.max(
//                (mission.autoFlightSpeed * (abs(altitudeDiff) / (originalAltitudeDiff))).toFloat(),
//                0.5f
//            )
//            directions.altitude = if (altitudeDiff > 0) speed else -speed
//        }

        //when the longitude difference becomes insignificant:
        if (abs(longitudeDiff) < 0.000001) {
            Log.i(TAG, "finished travelling LONGITUDE")
            directions.pitch = 0f
            travelledLongitude = true
        }
        if (abs(latitudeDiff) < 0.000001) {
            Log.i(TAG, "finished travelling LATITUDE")
            directions.roll = 0f
            travelledLatitude = true
        }
        if (abs(altitudeDiff) < 0.5) {
            Log.i(TAG, "finished travelling ALTITUDE")
//            directions.altitude = 0f
            travelledAltitude = true
        }
        else{
            if (travelledLongitude && travelledLatitude) {
                if(abs(altitudeDiff) < 1.0){
                    if(!originalAltitudeDeadLock){
                        originalAltitudeDeadLock = true
                        originalAltitudeDeadLock_start = System.currentTimeMillis()
                    }
                    else{
                        var secsPassed = (System.currentTimeMillis()-originalAltitudeDeadLock_start)/1000
                        altitudeDeadLockHeight += secsPassed * (abs(altitudeDiff) / altitudeDiff) / 5
                        if(abs(originalAltitudeDeadLock_start-System.currentTimeMillis())>originalAltitudeDeadLock_timeout){
                            Log.i(TAG, "finished travelling ALTITUDE with TIMEOUT")
                            showToast(mContext,"finished travelling ALTITUDE with TIMEOUT")
                            //            directions.altitude = 0f
                            travelledAltitude = true
                        }
                    }
                }
                else{
                    Log.i(TAG, "Altitude is far away, distance: ${abs(altitudeDiff).toString()}")
                    if(!originalAltitudeDeadLock){
                        originalAltitudeDeadLock = true
                        originalAltitudeDeadLock_start = System.currentTimeMillis()
                    }
                    else{
                        var secsPassed = (System.currentTimeMillis()-originalAltitudeDeadLock_start)/1000
                        altitudeDeadLockHeight += secsPassed * (abs(altitudeDiff) / altitudeDiff) / 5
                        if(abs(originalAltitudeDeadLock_start-System.currentTimeMillis())>originalAltitudeDeadLock_timeout*2){
                            Log.i(TAG, "finished travelling ALTITUDE with TIMEOUT EXTREME, distance: ${abs(altitudeDiff).toString()}")
                            showToast(mContext,"finished travelling ALTITUDE with TIMEOUT EXTREME, distance: ${abs(altitudeDiff).toString()}")
                            //            directions.altitude = 0f
                            travelledAltitude = true
                        }
                    }
                }
            }
        }

        //when the latitude difference becomes insignificant and there
        //... is no longitude difference (current waypoint has been reached):
        if (travelledLatitude && travelledLongitude && travelledAltitude) {
            //move to the next waypoint in the waypoints list
            Log.i(TAG, "Reached Waypoint: ${waypointTracker.toString()}")
            waypointTracker++
            if (waypointTracker < waypoints.size) {
                currentWaypoint = waypoints[waypointTracker]
                originalLongitudeDiff = -1.0
                originalLatitudeDiff = -1.0
                originalAltitudeDiff = -1.0
                travelledLongitude = false
                travelledLatitude = false
                travelledAltitude = false
                directions = Direction()
                originalAltitudeDeadLock = false
                altitudeDeadLockHeight = 0.0
            } else { //If all waypoints have been reached, stop the mission
                Log.i(TAG, "All waypoints have been reached, stopping.")
                state = WaypointMissionState.EXECUTION_STOPPING
                operatorListener?.onExecutionFinish(null)
                stopMission(null)
                landing(null)
                isLanding = true
                sendDataTimer.cancel()
                if (isLanding && currentLocation.altitude == 0f) {
                    if (!isLanded) {
                        Log.i(TAG, "Drone is landed.")
                        sendDataTimer.cancel()
                        isLanded = true
                    }
                }
                removeObserver()
            }
            sendDataTimer.cancel() //cancel all scheduled data tasks
        } else {
            // checking for pause state
            if (state == WaypointMissionState.EXECUTING) {
                directions.yaw = currentWaypoint.heading.toFloat()
                directions.altitude = (currentWaypoint.altitude+altitudeDeadLockHeight).toFloat()
            } else if (state == WaypointMissionState.EXECUTION_PAUSED) {
                directions = Direction(0f, 0f, currentWaypoint.heading.toFloat(), (currentWaypoint.altitude+altitudeDeadLockHeight).toFloat())
            }
            move(directions)

        }

    }

    private fun removeObserver() {
        Log.i(TAG, "Removing observer")
        droneLocationLiveData.removeObserver(locationObserver)
        gimbalObserver?.let {
            gimbalPitchLiveData.removeObserver(it)
        }
        observeGimbal = false
        isAirborne = false
        waypointTracker = 0
        isLanded = false
        isLanding = false
        travelledLatitude = false
        travelledLongitude = false
    }


    @SuppressLint("LongLogTag")
    // Function used to move the drone in the provided direction
    private fun move(dir: Direction) {
        var altitude_target=dir.altitude
        if(altitudeHack){
            altitude_target= (altitude_target+0.5).toFloat()
            Log.d(TAG, "PITCH: ${dir.pitch}, ROLL: ${dir.roll}, YAW: ${dir.yaw}, ALT-hacked: ${altitude_target}")
        }
        else{
            Log.d(TAG, "PITCH: ${dir.pitch}, ROLL: ${dir.roll}, YAW: ${dir.yaw}, ALT: ${altitude_target}")
        }

        sendDataTask =
            SendDataTask(dir.pitch, dir.roll, dir.yaw, altitude_target)
        sendDataTimer.schedule(sendDataTask, 0, 200)
    }


    // Function used to stop the current waypoint mission and land the drone
    fun stopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?) {
        Log.i(TAG, "Stopping mission")
        state = WaypointMissionState.EXECUTION_STOPPING
        operatorListener?.onExecutionFinish(null)
//        if (!isLanding) {
//            showToast(mContext, "trying to land")
//        }
//        DJIDemoApplication.getFlightController()?.setGoHomeHeightInMeters(20) {
//            DJIDemoApplication.getFlightController()?.startGoHome(callback)
//        }
//        landing()
//        forceStopMission(null)
    }

    fun landing(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?){
        Log.i(TAG, "Landing")
        DJIDemoApplication.getFlightController()?.let { controller ->
            controller.startLanding { djiError ->
                if (djiError != null) {
                    Log.i(TAG, djiError.description)
                    showToast(mContext,"Landing Error: ${djiError.description}")
                } else {
                    Log.i(TAG,"Start Landing Success")
                    showToast(mContext,"Start Landing Success")
                }
            }
        }
    }

    fun cancellanding(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?){
        Log.i(TAG, "Canceling Landing")
        DJIDemoApplication.getFlightController()?.let { controller ->
            controller.cancelLanding { djiError ->
                if (djiError != null) {
                    Log.i(TAG, djiError.description)
                    showToast(mContext,"Landing Error: ${djiError.description}")
                } else {
                    Log.i(TAG,"Start Landing Success")
                    showToast(mContext,"Start Landing Success")
                }
            }
        }
    }

    fun forceStopMission(callback: CommonCallbacks.CompletionCallback<DJIMissionError>?){
        Log.i(TAG, "ForceStop mission")
        state = WaypointMissionState.EXECUTION_STOPPING
        operatorListener?.onExecutionFinish(null)
//        stopMission(null)
//        isLanding = true
        sendDataTimer.cancel()
        Log.i(TAG, "Landing for control takeover")
        landing(null)
        Thread.sleep(500)
        Log.i(TAG, "Stop Landing for control takeover")
        cancellanding(null)
        Log.i(TAG, "Operation stopped")
        showToast(mContext,"Operation stopped!")
//        if (!isLanding)
//            showToast(mContext, "trying to land")
//        if (isLanding && currentWaypoint.altitude == 0f) {
//            if (!isLanded) {
//                sendDataTimer.cancel()
//                isLanded = true
//            }
//        }
        removeObserver()
    }
    /*
     * Roll: POSITIVE is SOUTH, NEGATIVE is NORTH, Range: [-30, 30]
     * Pitch: POSITIVE is EAST, NEGATIVE is WEST, Range: [-30, 30]
     * YAW: POSITIVE is RIGHT, NEGATIVE is LEFT, Range: [-360, 360]
     * THROTTLE: UPWARDS MOVEMENT
     */

    fun addListener(listener: WaypointMissionOperatorListener) {
        this.operatorListener = listener
    }

    fun removeListener() {
        this.operatorListener = null
    }

    class SendDataTask(pitch: Float, roll: Float, yaw: Float, throttle: Float) : TimerTask() {
        private val mPitch = pitch
        private val mRoll = roll
        private val mYaw = yaw
        private val mThrottle = throttle
        override fun run() {
            DJIDemoApplication.getFlightController()?.sendVirtualStickFlightControlData(
                FlightControlData(
                    mPitch,
                    mRoll,
                    mYaw,
                    mThrottle
                ),
                null
            )
            this.cancel()
        }
    }

    inner class Direction(
        var pitch: Float = 0f,
        var roll: Float = 0f,
        var yaw: Float = 0f,
        var altitude: Float = currentWaypoint.altitude
    )

    private fun showToast(context: Context, message: String){
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}