package com.example.mapviewdemo
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Observer
import dji.sdk.sdkmanager.DJISDKManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ConnectionActivity : AppCompatActivity() {
    private lateinit var mTextConnectionStatus: TextView
    private lateinit var mTextProduct: TextView
    private lateinit var mTextModelAvailable: TextView
    private lateinit var mBtnOpen: Button
    private lateinit var mVersionTv: TextView

    private val model: ConnectionViewModel by viewModels() // this is all our data stored in the view model

    companion object { // a tag for debugging
        const val TAG = "ConnectionActivity"
    }

    fun logging() {
        var logfolder = "Logs/"
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss")
        val currentDateandTime = sdf.format(Date()).toString()
        var filename = "logcat_" + currentDateandTime + ".txt"
        var filename_all = "logcat_" + currentDateandTime + "_all.txt"
        val mydir = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            ),
            "$logfolder"
        )
        if (!mydir.exists()) {
            mydir.mkdirs()
        }
        val logfile = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            ),
            "$logfolder$filename"
        )
        val logfileAll = File(
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            ),
            "$logfolder$filename_all"
        )
        try {
//            Runtime.getRuntime().exec("logcat -c")
//            Thread.sleep(200)
            Runtime.getRuntime().exec("logcat -f ${logfile.absolutePath} *:S $TAG:D ${Waypoint1Activity.TAG}:D ${MavicMiniMissionOperator.TAG}:D")
            Runtime.getRuntime().exec("logcat -f" + logfileAll.absolutePath)
        } catch (e: Exception) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        logging()
        Log.d(TAG,"Initializing Connection Activity")
        super.onCreate(savedInstanceState)
        Log.i(TAG,"Setting Content View")
        setContentView(R.layout.activity_connection)
        Log.d(TAG,"Requesting permissions")
        ActivityCompat.requestPermissions(this, // request all the permissions that we'll need
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                Manifest.permission.READ_PHONE_STATE
            ), 1)

        initUI() // initialize the UI
        model.registerApp()
        observers()

    }

    private fun initUI() { // Initializes the UI with all the string values
        Log.d(TAG,"Initializing UI")
        mTextConnectionStatus = findViewById(R.id.text_connection_status)
        mTextModelAvailable = findViewById(R.id.text_model_available)
        mTextProduct = findViewById(R.id.text_product_info)
        mBtnOpen = findViewById(R.id.btn_open)
        mVersionTv = findViewById(R.id.textView2)
        mVersionTv.text = resources.getString(R.string.sdk_version, DJISDKManager.getInstance().sdkVersion)
        mBtnOpen.isEnabled = false
        mBtnOpen.setOnClickListener { // navigate to the main activity
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    private fun observers() {
        model.connectionStatus.observe(this, Observer<Boolean> { isConnected -> // observe the connection status and enable the button on connection
            if (isConnected) {
                mTextConnectionStatus.text = "Status: Connected"
                mBtnOpen.isEnabled = true
            }
            else {
                mTextConnectionStatus.text = "Status: Disconnected"
                mBtnOpen.isEnabled = false
            }
        })

        model.product.observe(this, Observer { baseProduct -> // observe the product and populate the appropriate text fields
            if (baseProduct != null && baseProduct.isConnected) {
                mTextModelAvailable.text = baseProduct.firmwarePackageVersion
                mTextProduct.text = baseProduct.model.displayName
            }

        })
    }
}