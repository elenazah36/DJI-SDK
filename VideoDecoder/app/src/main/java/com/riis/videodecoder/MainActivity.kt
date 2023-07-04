package com.riis.videodecoder

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.media.MediaFormat
import android.os.*
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.riis.videodecoder.media.DJIVideoStreamDecoder
import com.riis.videodecoder.media.NativeHelper
import dji.common.airlink.PhysicalSource
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJIError
import dji.common.product.Model
import dji.sdk.airlink.OcuSyncLink
import dji.sdk.base.BaseProduct
import dji.sdk.camera.Camera
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager
import dji.sdk.sdkmanager.DJISDKManager
import org.pytorch.IValue
import org.pytorch.LiteModuleLoader
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.*
import java.nio.ByteBuffer


class MainActivity : Activity(), DJICodecManager.YuvDataCallback {

    class AnalysisResult(val mResults: java.util.ArrayList<Result>)

    private var surfaceCallback: SurfaceHolder.Callback? = null

    private enum class DemoType {
        USE_TEXTURE_VIEW, USE_SURFACE_VIEW, USE_SURFACE_VIEW_DEMO_DECODER
    }

    private var standardVideoFeeder: VideoFeeder.VideoFeed? = null
    private var mReceivedVideoDataListener: VideoFeeder.VideoDataListener? = null
    private var titleTv: TextView? = null
    private var mainHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_WHAT_SHOW_TOAST -> Toast.makeText(
                    applicationContext, msg.obj as String, Toast.LENGTH_SHORT
                ).show()
//                MSG_WHAT_UPDATE_TITLE -> if (titleTv != null) {
//                    titleTv!!.text = msg.obj as String
//                }
                else -> {}
            }
        }
    }
    private var videostreamPreviewTtView: TextureView? = null
    private var textureView: TextureView? = null
    private var mImageView: ImageView? = null
    private var videostreamPreviewSf: SurfaceView? = null
    private var videostreamPreviewSh: SurfaceHolder? = null

    private lateinit var surfaceHolder: SurfaceHolder

    private var mCamera: Camera? = null
    private var mCodecManager: DJICodecManager? = null
//    private var savePath: TextView? = null
//    private var screenShot: Button? = null
//    private var stringBuilder: StringBuilder? = null
    private var videoViewWidth = 0
    private var videoViewHeight = 0
    private var count = 0
    private var mResultView: ResultView? = null

//    private var module = LiteModuleLoader.load(assetFilePath(this, "yolov5s.torchscript.ptl"))
    private var module: Module? = null

    private fun loadModel(){
        Log.d(TAG,"Loading Model")
//        var fileName = assetFilePath(this, "yolov5s.torchscript.ptl")
        var fileName = assetFilePath(this, "yolov5.torchscript.ptl")
//        var fileName = assetFilePath(this, "best.pt")
//        setResultToToast("the file path: $fileName")
        var file = File(fileName)
        var fileExists = file.exists()

        if(fileExists){
            Log.i(TAG,"$fileName exists.")
//            setResultToToast("$fileName exists.")
        } else {
            Log.e(TAG,"$fileName does not exist.")
//            setResultToToast("$fileName does not exist.")
        }
        try{
            Log.d(TAG,"Trying to load model.")
            module = LiteModuleLoader.load(fileName)
            Log.i(TAG,"Module loaded from :$fileName")
//            setResultToToast("Module loaded from :$fileName")
        }catch (e: Exception){
            Log.e(TAG, "Error loading the model: $e")
//            setResultToToast("Error loading the model: $e")
        }


        Log.d(TAG,"Loading Classes")
        fileName = assetFilePath(this, "classes.txt")
        file = File(fileName)
        fileExists = file.exists()
        if(fileExists){
            Log.i(TAG,"$fileName exists.")
            setResultToToast("$fileName exists.")
        } else {
            Log.e(TAG,"$fileName does not exist.")
//            setResultToToast("$fileName does not exist.")
        }
        try
        {
            Log.d(TAG,"Trying to load classes.")
//            val br = BufferedReader(InputStreamReader(fileName?.let { assets.open(it) }))
//            Log.d(TAG,"Buffered Reader created")
            val classes: MutableList<String> = ArrayList()
            File(fileName).forEachLine {classes.add(it)}
            mResultView!!.mClasses = classes.toTypedArray()
//            classes.toArray(PrePostProcessor.mClasses)
            Log.i(TAG,"Classes ({${classes.size}}) loaded from :$fileName")
        }catch (  e:java.io.IOException)
        {
            Log.e(TAG, "Error reading classes: ", e)
            setResultToToast("Error reading classes: $e")
            finish()
        }
    }



    override fun onResume() {
//        setResultToToast("onResume")
//        runOnUiThread { Toast.makeText(this, "onResume", Toast.LENGTH_SHORT).show() }
        super.onResume()
        initSurfaceOrTextureView()
        notifyStatusChange()
    }

    private fun initSurfaceOrTextureView() {
//        setResultToToast("initSurfaceOrTextureView")
//        runOnUiThread { Toast.makeText(this, "initSurfaceOrTextureView", Toast.LENGTH_SHORT).show() }
        when (demoType) {
            DemoType.USE_SURFACE_VIEW -> initPreviewerSurfaceView()
            DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                /**
                 * we also need init the textureView because the pre-transcoded video steam will display in the textureView
                 */
                initPreviewerTextureView()
                /**
                 * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                 * on surfaceView
                 */
                initPreviewerSurfaceView()
            }
            DemoType.USE_TEXTURE_VIEW -> initPreviewerTextureView()
            else -> {}
        }
    }

    override fun onPause() {
//        setResultToToast("onPause")
//        runOnUiThread { Toast.makeText(this, "onPause", Toast.LENGTH_SHORT).show() }
        if (mCamera != null) {
            VideoFeeder.getInstance().primaryVideoFeed
                .removeVideoDataListener(mReceivedVideoDataListener)
            standardVideoFeeder?.removeVideoDataListener(mReceivedVideoDataListener)
        }
        super.onPause()
    }

    override fun onDestroy() {
//        setResultToToast("onDestroy")
//        runOnUiThread { Toast.makeText(this, "onDestroy", Toast.LENGTH_SHORT).show() }
        if (mCodecManager != null) {
            mCodecManager!!.cleanSurface()
            mCodecManager!!.destroyCodec()
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
//        setResultToToast("OnCreate")
//        runOnUiThread { Toast.makeText(this, "OnCreate", Toast.LENGTH_SHORT).show() }
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        initUi()
        loadModel()
        if (MainActivity.isM300Product) {
            val ocuSyncLink: OcuSyncLink? =
                VideoDecodingApplication.productInstance?.airLink?.ocuSyncLink
            // If your MutltipleLensCamera is set at right or top, you need to change the PhysicalSource to RIGHT_CAM or TOP_CAM.
            if (ocuSyncLink != null) {
                ocuSyncLink.assignSourceToPrimaryChannel(
                    PhysicalSource.LEFT_CAM, PhysicalSource.FPV_CAM
                ) { error: DJIError? ->
                    if (error == null) {
                        showToast("assignSourceToPrimaryChannel success.")
                    } else {
                        showToast("assignSourceToPrimaryChannel fail, reason: " + error.description)
                    }
                }
            }
        }
    }

    private fun showToast(s: String) {
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_SHOW_TOAST, s)
        )
    }

    private fun updateTitle(s: String) {
//        setResultToToast("updateTitle")
//        runOnUiThread { Toast.makeText(this, "updateTitle", Toast.LENGTH_SHORT).show() }
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        )
    }

    private fun initUi() {
//        setResultToToast("initUi")
//        runOnUiThread { Toast.makeText(this, "initUi", Toast.LENGTH_SHORT).show() }
//        savePath = findViewById<View>(R.id.activity_main_save_path) as TextView
//        screenShot = findViewById<View>(R.id.activity_main_screen_shot) as Button
//        screenShot!!.isSelected = false
        titleTv = findViewById<View>(R.id.title_tv) as TextView
        videostreamPreviewTtView = getCameraPreviewTextureView()
//        videostreamPreviewTtView = findViewById<View>(R.id.livestream_preview_ttv) as TextureView
//        videostreamPreviewTtView!!.setAlpha(0.0f)
        videostreamPreviewSf = findViewById<View>(R.id.livestream_preview_sf) as SurfaceView
        VideoFeeder.getInstance().transcodingDataRate = 3.0f
        showToast("set rate to 3Mbps")
//        videostreamPreviewSf!!.isClickable = true
//        videostreamPreviewSf!!.setOnClickListener {
//            val rate: Float = VideoFeeder.getInstance().transcodingDataRate
//            showToast("current rate:" + rate + "Mbps")
//            if (rate < 10) {
//                VideoFeeder.getInstance().transcodingDataRate = 10.0f
//                showToast("set rate to 10Mbps")
//            } else {
//                VideoFeeder.getInstance().transcodingDataRate = 3.0f
//                showToast("set rate to 3Mbps")
//            }
//        }
//        textureView = getCameraPreviewTextureView()
//        val surface: SurfaceTexture? = videostreamPreviewTtView?.surfaceTexture
//        if (surface != null) {
//            textureView?.setSurfaceTexture(surface)
//        }
//        mImageView = findViewById(R.id.imageView)
        mResultView = findViewById(R.id.resultView)
        mResultView?.setVisibility(View.VISIBLE)
        updateUIVisibility()


//        handleYUVClick()
//        Log.d(TAG, "Adding YUV data listener")
//        setResultToToast("Adding YUV data listener")
//        DJIVideoStreamDecoder.instance?.setYuvDataListener(this@MainActivity)
//        mCodecManager!!.yuvDataCallback = this
//        Log.d(TAG, "Added YUV data listener")
//        mCodecManager!!.enabledYuvData(false)
//        Log.d(TAG, "Enabled YUV data")
    }

    private fun updateUIVisibility() {
//        setResultToToast("updateUIVisibility")
//        runOnUiThread { Toast.makeText(this, "updateUIVisibility", Toast.LENGTH_SHORT).show() }
        when (demoType) {
            DemoType.USE_SURFACE_VIEW -> {
                videostreamPreviewSf!!.visibility = View.VISIBLE
                videostreamPreviewTtView!!.visibility = View.GONE
            }
            DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                /**
                 * we need display two video stream at the same time, so we need let them to be visible.
                 */
                videostreamPreviewSf!!.visibility = View.VISIBLE
                videostreamPreviewTtView!!.visibility = View.VISIBLE
            }
            DemoType.USE_TEXTURE_VIEW -> {
                videostreamPreviewSf!!.visibility = View.GONE
                videostreamPreviewTtView!!.visibility = View.VISIBLE
            }
            else -> {}
        }
    }

    private var lastupdate: Long = 0
    private fun notifyStatusChange() {
//        setResultToToast("notifyStatusChange")
//        runOnUiThread { Toast.makeText(this, "notifyStatusChange", Toast.LENGTH_SHORT).show() }
        val product: BaseProduct? = VideoDecodingApplication.productInstance
        Log.d(
            TAG,
            "notifyStatusChange: " + when {
                product == null -> "Disconnect"
                product.model == null -> "null model"
                else -> product.model.name
            }
        )
        if (product != null) {
            if (product.isConnected && product.model != null) {
                updateTitle(product.model.name + " Connected " + demoType?.name)
            } else {
                updateTitle("Disconnected")
            }
        }

        // The callback for receiving the raw H264 video data for camera live view
        mReceivedVideoDataListener =
            VideoFeeder.VideoDataListener {
                    videoBuffer, size ->
                if (System.currentTimeMillis() - lastupdate > 1000) {
                    Log.d(
                        TAG,
                        "camera recv video data size: $size"
                    )
                    lastupdate = System.currentTimeMillis()
                }
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW ->{
                        mCodecManager?.sendDataToDecoder(videoBuffer, size)

                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER ->
                        /**
                         * we use standardVideoFeeder to pass the transcoded video data to DJIVideoStreamDecoder, and then display it
                         * on surfaceView
                         */
                        DJIVideoStreamDecoder.instance?.parse(videoBuffer, size)
                    DemoType.USE_TEXTURE_VIEW -> mCodecManager?.sendDataToDecoder(videoBuffer, size)
                    else -> {}
                }

            }
        if (product != null) {
            if (!product.isConnected) {
                mCamera = null
                showToast("Disconnected")
            } else {
                if (!product.model.equals(Model.UNKNOWN_AIRCRAFT)) {
                    mCamera = product.camera
                    if (mCamera != null) {
                        if (mCamera!!.isFlatCameraModeSupported) {
                            mCamera!!.setFlatMode(
                                SettingsDefinitions.FlatCameraMode.PHOTO_SINGLE
                            ) { djiError: DJIError? ->
                                if (djiError != null) {
                                    showToast("can't change flat mode of camera, error:" + djiError.description)
                                }
                            }
                        } else {
                            mCamera!!.setMode(
                                SettingsDefinitions.CameraMode.SHOOT_PHOTO
                            ) { djiError: DJIError? ->
                                if (djiError != null) {
                                    showToast("can't change mode of camera, error:" + djiError.description)
                                }
                            }
                        }
                    }

                    //When calibration is needed or the fetch key frame is required by SDK, should use the provideTranscodedVideoFeed
                    //to receive the transcoded video feed from main camera.
                    if (demoType == DemoType.USE_SURFACE_VIEW_DEMO_DECODER && isTranscodedVideoFeedNeeded) {
                        standardVideoFeeder = VideoFeeder.getInstance().provideTranscodedVideoFeed()
                        standardVideoFeeder!!.addVideoDataListener(mReceivedVideoDataListener!!)
                        return
                    }
                    VideoFeeder.getInstance().primaryVideoFeed
                        .addVideoDataListener(mReceivedVideoDataListener!!)
                }
            }
        }
    }

    /**
     * Init a fake texture view to for the codec manager, so that the video raw data can be received
     * by the camera
     */
    private fun initPreviewerTextureView() {
//        setResultToToast("initPreviewerTextureView")
//        runOnUiThread { Toast.makeText(this, "initPreviewerTextureView", Toast.LENGTH_SHORT).show() }
        videostreamPreviewTtView!!.surfaceTextureListener = object :
            TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                Log.d(TAG, "real onSurfaceTextureAvailable")
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable: width $videoViewWidth height $videoViewHeight"
                )
                if (mCodecManager == null) {
                    //run by default
                    mCodecManager = DJICodecManager(applicationContext, surface, width, height)
                    //For M300RTK, you need to actively request an I frame.
                    mCodecManager!!.resetKeyFrame()
                    setResultToToast("Adding YUV data listener in InitPreviewerTextureView")
//                    mCodecManager!!.enabledYuvData(true)
//                    mCodecManager!!.yuvDataCallback = this@MainActivity
                }
//                val result = processimages()
//                runOnUiThread { result?.let { AnalysisResult(it) }
//                    ?.let { applyToUiAnalyzeImageResult(it) } }

            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable2: width $videoViewWidth height $videoViewHeight"
                )
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                mCodecManager?.cleanSurface()
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {val result = processimages()}
        }
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private fun initPreviewerSurfaceView() {
//        setResultToToast("initPreviewerSurfaceView")
//        runOnUiThread { Toast.makeText(this, "initPreviewerSurfaceView", Toast.LENGTH_SHORT).show() }
        videostreamPreviewSh = videostreamPreviewSf!!.holder
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {

                Log.d(TAG, "real onSurfaceTextureAvailable")
                videoViewWidth = videostreamPreviewSf!!.width
                videoViewHeight = videostreamPreviewSf!!.height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable3: width $videoViewWidth height $videoViewHeight"
                )
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> if (mCodecManager == null) {
                        mCodecManager = DJICodecManager(
                            applicationContext, holder, videoViewWidth,
                            videoViewHeight
                        )
                        // not run by default
                        setResultToToast("Adding YUV data listener in InitPreviewerSurfaceView")
                        mCodecManager!!.enabledYuvData(true)
                        mCodecManager!!.yuvDataCallback = this@MainActivity
                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                        // This demo might not work well on P3C and OSMO.
                        NativeHelper.instance?.init()
                        DJIVideoStreamDecoder.instance?.init(applicationContext, holder.surface)
                        DJIVideoStreamDecoder.instance?.resume()
                    }
                    else -> {}
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                videoViewWidth = width
                videoViewHeight = height
                Log.d(
                    TAG,
                    "real onSurfaceTextureAvailable4: width $videoViewWidth height $videoViewHeight"
                )
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> {}
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> DJIVideoStreamDecoder.instance
                        ?.changeSurface(holder.surface)
                    else -> {}
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                when (demoType) {
                    DemoType.USE_SURFACE_VIEW -> if (mCodecManager != null) {
                        mCodecManager!!.cleanSurface()
                        mCodecManager!!.destroyCodec()
                        mCodecManager = null
                    }
                    DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
                        DJIVideoStreamDecoder.instance?.stop()
                        NativeHelper.instance?.release()
                    }
                    else -> {}
                }
            }
        }
        videostreamPreviewSh!!.addCallback(surfaceCallback)
    }

    @Throws(IOException::class)
    fun assetFilePath(context: Context, assetName: String?): String? {
//        runOnUiThread { Toast.makeText(this, "loading model", Toast.LENGTH_SHORT).show() }
        Thread.sleep(1000)
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        context.assets.open(assetName!!).use { `is` ->
            FileOutputStream(file).use { os ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (`is`.read(buffer).also { read = it } != -1) {
                    os.write(buffer, 0, read)
                }
                os.flush()
            }
            return file.absolutePath
        }
    }


    override fun onYuvDataReceived(
        format: MediaFormat,
        yuvFrame: ByteBuffer?,
        dataSize: Int,
        width: Int,
        height: Int
    ) {
        Log.d(TAG, "YUV data received")
//        setResultToToast("YUV data received")


        // Convert the YUV data ByteBuffer to a byte array
        Log.d(TAG, "Convert the YUV data ByteBuffer to a byte array")
        val yuvData = ByteArray(dataSize)
        yuvFrame?.get(yuvData)
        val length = width * height
        val u = ByteArray(width * height / 4)
        val v = ByteArray(width * height / 4)

        for (i in u.indices) {
            u[i] = yuvData!![length + i]
            v[i] = yuvData!![length + u.size + i]
        }
        for (i in u.indices) {
            yuvData[length + 2 * i] = v[i]
            yuvData[length + 2 * i + 1] = u[i]
        }
        // Create a YuvImage object from the YUV data
        Log.d(TAG, "Create a YuvImage object from the YUV data")
        var yuvImage: YuvImage? = null
        try{
            yuvImage = YuvImage(yuvData, ImageFormat.NV21, width, height, null)
        }catch (e: Exception){Log.e(TAG, "Error creating a YuvImage object from the YUV data: $e")}
        val outputStream = ByteArrayOutputStream()
        // Convert the YuvImage to a ByteArrayOutputStream
        Log.d(TAG, "Convert the YuvImage to a ByteArrayOutputStream")
        if (yuvImage != null) {
            try {
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)
            }catch (e: Exception){Log.e(TAG, "Error converting the YuvImage to a ByteArrayOutputStream: $e")}
        }
        // Convert the ByteArrayOutputStream to a byte array
        Log.d(TAG, "Convert the ByteArrayOutputStream to a byte array")
        val jpegByteArray = outputStream.toByteArray()
        // Create a Bitmap from the byte array
        Log.d(TAG, "Create a Bitmap from the byte array")
        val bitmap = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)
        // Bitmap for further processing or display
//        val surface: SurfaceTexture? = videostreamPreviewTtView?.surfaceTexture
//        if (surface != null) {
//            textureView?.setSurfaceTexture(surface)
//        }
//        mImageView?.setImageDrawable(null)
//        mImageView?.setImageBitmap(bitmap)
        Log.d(TAG, "Bitmap for further processing or display")
//        module?.let { performObjectDetection(bitmap, it) }
        module?.let { val res = performObjectDetection(bitmap, it) }
//        performObjectDetection(bitmap, LiteModuleLoader.load(assetFilePath(this, "yolov5s.torchscript.ptl")))
    }

    fun processimages(): java.util.ArrayList<Result>? {
        val bitmap: Bitmap? = videostreamPreviewTtView?.getBitmap()
        Log.d(TAG, "Bitmap from texture view")
        if(bitmap==null){
            Log.d(TAG, "Bitmap from texture view is NULL")
            setResultToToast("Bitmap from texture view is NULL")
        }
        module?.let {
            if (bitmap != null) {
                val res = performObjectDetection(bitmap, it)
                mResultView!!.setResults(res)
                mResultView!!.invalidate()
                return res
            }
        }
        val res: ArrayList<Result>? = null
        return res
    }

    protected fun getCameraPreviewTextureView(): TextureView? {
        mResultView = findViewById(R.id.resultView)
        return (findViewById(R.id.object_detection_texture_view_stub) as ViewStub)
            .inflate()
            .findViewById(R.id.object_detection_texture_view)
    }
    protected fun applyToUiAnalyzeImageResult(result: AnalysisResult) {
        mResultView!!.setResults(result.mResults)
        mResultView!!.invalidate()
    }

    fun performObjectDetection(bitmap: Bitmap, model: Module):ArrayList<Result> {
        // Preprocess the input image
        Log.d(TAG, "Detecting Object")
        Log.d(TAG, "Create Scaled Bitmap")
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap,
            INPUT_IMAGE_SIZE,
            INPUT_IMAGE_SIZE, false)
        Log.d(TAG, "Rotate Bitmap")
        val rotatedBitmap = rotateBitmap(resizedBitmap, 0f) // Adjust rotation if needed
        Log.d(TAG, "Scaling rotated bitmap")
//        val resizedrotatedBitmap = Bitmap.createScaledBitmap(rotatedBitmap,
//            INPUT_IMAGE_SIZE,
//            INPUT_IMAGE_SIZE, false)
        Log.d(TAG, "normalizing tensor")
        val normalizedTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
            TensorImageUtils.TORCHVISION_NORM_STD_RGB)
//        val normalizedTensor = preprocessImage(rotatedBitmap)
        val shape = "The size of the tensor: (${normalizedTensor.shape().get(0)}, ${normalizedTensor.shape().get(1)}, ${normalizedTensor.shape().get(2)}, ${normalizedTensor.shape().get(3)})"
        Log.d(TAG, "The size of the tensor: $shape")
//        setResultToToast("Detecting Objects: ${normalizedTensor.shape()}")
        // Run the inference
        Log.d(TAG, "Running the inference")
//        setResultToToast("Running the inference")

        var ivalue: IValue = IValue.from(normalizedTensor)
        var outputTuple: Array<IValue> = model.forward(ivalue).toTuple()
        var outputTensor: Tensor = outputTuple[0].toTensor()
        var outputs = outputTensor.dataAsFloatArray
        Log.d(TAG, "Detected Objects: ${outputs.get(0)}")

        val imgScaleX: Float = resizedBitmap.width.toFloat() / PrePostProcessor.mInputWidth
        val imgScaleY: Float = resizedBitmap.height.toFloat() / PrePostProcessor.mInputHeight
        Log.d(TAG, "Image scales: $imgScaleX and $imgScaleY")
        var ivScaleX: Float = 1.0F
        var ivScaleY: Float = 1.0F
        try{
            ivScaleX = mResultView!!.getWidth().toFloat()/ bitmap.getWidth().toFloat()
            ivScaleY = mResultView!!.getHeight().toFloat() / bitmap.getHeight().toFloat()
        }catch (e: Exception){Log.e(TAG, "Error running the iv scaling: $e")}


        // Process the output tensor and extract detected objects
        Log.d(TAG, "outputs to NMS Predictions")
        val results: ArrayList<Result> = PrePostProcessor.outputsToNMSPredictions(
            outputs,
            imgScaleX,
            imgScaleY,
            ivScaleX,
            ivScaleY,
            0.toFloat(),
            0.toFloat()
        )
        if(results.size>0){
//            setResultToToast("Detected ${results.size} Objects")
            Log.d(TAG, "Detected ${results.size} Objects")
//            mResultView!!.setResults(results)
//            mResultView!!.invalidate()
//            runOnUiThread { applyToUiAnalyzeImageResult(AnalysisResult(results)) }
        }
        return results


//        Log.d(TAG, "Detecting Objects: $outputs")
//        setResultToToast("Detecting Objects: $outputs")
//        Log.d(TAG, "Extracting the detected objects")
//        val detectedObjects = outputTensor?.let { processOutputTensor(it) }
//        return detectedObjects
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun preprocessImage(bitmap: Bitmap): Tensor {
        // Resize and normalize the image
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap,
            INPUT_IMAGE_SIZE,
            INPUT_IMAGE_SIZE, false)
        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
            TensorImageUtils.TORCHVISION_NORM_STD_RGB)
        return inputTensor
    }

    private fun processOutputTensor(outputTensor: Tensor): List<DetectedObject> {
//        setResultToToast("processOutputTensor")
//        runOnUiThread { Toast.makeText(this, "processOutputTensor", Toast.LENGTH_SHORT).show() }
        val results = mutableListOf<DetectedObject>()

        val outputData = outputTensor.dataAsFloatArray
        val numClasses = outputData.size / (5 + NUM_ANCHORS)

        for (y in 0 until GRID_SIZE) {
            for (x in 0 until GRID_SIZE) {
                for (anchorIndex in 0 until NUM_ANCHORS) {
                    val baseIndex = y * GRID_SIZE * (5 + NUM_ANCHORS) + x * (5 + NUM_ANCHORS) + anchorIndex * (5 + numClasses)
                    val confidence = outputData[baseIndex + 4]
                    if (confidence > CONFIDENCE_THRESHOLD) {
                        val xCenter = (outputData[baseIndex] + x) / GRID_SIZE
                        val yCenter = (outputData[baseIndex + 1] + y) / GRID_SIZE
                        val width = outputData[baseIndex + 2].coerceAtLeast(0f)
                        val height = outputData[baseIndex + 3].coerceAtLeast(0f)
                        val classProbabilities = outputData.sliceArray(baseIndex + 5 until baseIndex + 5 + numClasses)

                        val maxClassProbability = classProbabilities.maxOrNull()
                        val maxClassIndex =
                            maxClassProbability?.let { findIndex(classProbabilities, it) }

                        val left = (xCenter - width / 2) * IMAGE_WIDTH
                        val top = (yCenter - height / 2) * IMAGE_HEIGHT
                        val right = (xCenter + width / 2) * IMAGE_WIDTH
                        val bottom = (yCenter + height / 2) * IMAGE_HEIGHT

                        val detectedObject = maxClassIndex?.let { getClassLabel(it) }?.let {
                            DetectedObject(
                                className = it,
                                confidence = confidence,
                                boundingBox = RectF(left, top, right, bottom)
                            )
                        }
                        if (detectedObject != null) {
                            results.add(detectedObject)
                            showToast("Detected" + detectedObject.className)
                        }
                    }
                }
            }
        }

        return results
    }

    fun findIndex(list: FloatArray, element: Float): Int? {
        for (index in 0 until list.size)
            if (list[index] == element) {
                return index
            }
        return null
    }

    // Draw the bounding boxes, not used yet
    private fun drawBoundingBoxes(canvas: Canvas, detectionResults: List<DetectedObject>) {
//        setResultToToast("drawBoundingBoxes")
//        runOnUiThread { Toast.makeText(this, "drawBoundingBoxes", Toast.LENGTH_SHORT).show() }
        val paint = Paint()
        paint.color = Color.GREEN
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.0f
        for (detectionResult in detectionResults) {
            val boundingBox: RectF = detectionResult.boundingBox
            canvas.drawRect(boundingBox, paint)
        }
    }

    data class DetectedObject(
        val className: String,
        val confidence: Float,
        val boundingBox: RectF
    )


    private fun getClassLabel(classIndex: Int): String {
        // Map class index to class label
        // Modify this function according to your specific class labels
        return when (classIndex) {
            0 -> "person"
            1 -> "car"
            2 -> "dog"
            else -> "unknown"
        }
    }


//    fun onClick(v: View) {
//        if (v.id == R.id.activity_main_screen_shot) {
//            handleYUVClick()
//        } else {
//            var newDemoType: DemoType? = null
//            if (v.id == R.id.activity_main_screen_texture) {
//                newDemoType = DemoType.USE_TEXTURE_VIEW
//            } else if (v.id == R.id.activity_main_screen_surface) {
//                newDemoType = DemoType.USE_SURFACE_VIEW
//            } else if (v.id == R.id.activity_main_screen_surface_with_own_decoder) {
//                newDemoType = DemoType.USE_SURFACE_VIEW_DEMO_DECODER
//            }
//            if (newDemoType != null && newDemoType != demoType) {
//                // Although finish will trigger onDestroy() is called, but it is not called before OnCreate of new activity.
//                if (mCodecManager != null) {
//                    mCodecManager!!.cleanSurface()
//                    mCodecManager!!.destroyCodec()
//                    mCodecManager = null
//                }
//                demoType = newDemoType
//                finish()
//                overridePendingTransition(0, 0)
//                startActivity(intent)
//                overridePendingTransition(0, 0)
//            }
//        }
//    }
//
//    private fun handleYUVClick(){
//        //USE_SURFACE_VIEW used by default
//        Log.d(TAG, "Adding YUV data listener: $demoType")
//        setResultToToast("Adding YUV data listener: $demoType")
//        if (screenShot!!.isSelected) {
//            screenShot!!.text = "YUV Screen Shot"
//            screenShot!!.isSelected = false
//            when (demoType) {
//                DemoType.USE_SURFACE_VIEW, DemoType.USE_TEXTURE_VIEW -> {
//                    mCodecManager!!.enabledYuvData(false)
//                    mCodecManager!!.yuvDataCallback = null
//                    setResultToToast("Adding YUV data listener 1")
//                }
//                DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
//                    DJIVideoStreamDecoder.instance?.changeSurface(videostreamPreviewSh!!.surface)
//                    DJIVideoStreamDecoder.instance?.setYuvDataListener(null)
//                    setResultToToast("Adding YUV data listener 2")
//                }
//                else -> {}
//            }
//            savePath!!.text = ""
//            savePath!!.visibility = View.INVISIBLE
//            stringBuilder = null
//        } else {
//            screenShot!!.text = "Live Stream"
//            screenShot!!.isSelected = true
//            when (demoType) {
//                DemoType.USE_TEXTURE_VIEW, DemoType.USE_SURFACE_VIEW -> {
//                    mCodecManager!!.enabledYuvData(true)
//                    mCodecManager!!.yuvDataCallback = this
//                    setResultToToast("Adding YUV data listener 3")
//                }
//                DemoType.USE_SURFACE_VIEW_DEMO_DECODER -> {
//                    DJIVideoStreamDecoder.instance?.changeSurface(null)
//                    DJIVideoStreamDecoder.instance?.setYuvDataListener(this@MainActivity)
//                    setResultToToast("Adding YUV data listener 4")
//                }
//                else -> {}
//            }
//            savePath!!.text = ""
//            savePath!!.visibility = View.INVISIBLE
//        }
//    }

    private val isTranscodedVideoFeedNeeded: Boolean
        get() = if (VideoFeeder.getInstance() == null) {
            false
        } else VideoFeeder.getInstance().isFetchKeyFrameNeeded || VideoFeeder.getInstance()
            .isLensDistortionCalibrationNeeded

    companion object {
        val TAG: String = MainActivity::class.java.simpleName
        private const val MSG_WHAT_SHOW_TOAST = 0
        private const val MSG_WHAT_UPDATE_TITLE = 1
        private var demoType: DemoType? = DemoType.USE_TEXTURE_VIEW
        val isM300Product: Boolean
            get() {
                if (DJISDKManager.getInstance().product == null) {
                    return false
                }
                val model: Model = DJISDKManager.getInstance().product.model
                return model === Model.MATRICE_300_RTK
            }
        private const val INPUT_IMAGE_SIZE = 640
        private const val GRID_SIZE = 20
        private const val NUM_ANCHORS = 3
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val IMAGE_WIDTH = 640
        private const val IMAGE_HEIGHT = 480
    }
    private fun setResultToToast(string: String) {
        runOnUiThread { Toast.makeText(this, string, Toast.LENGTH_SHORT).show() }
    }
}