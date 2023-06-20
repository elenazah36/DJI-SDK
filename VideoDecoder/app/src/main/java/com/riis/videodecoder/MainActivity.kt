package com.riis.videodecoder

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.media.MediaFormat
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.widget.Button
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
                MSG_WHAT_UPDATE_TITLE -> if (titleTv != null) {
                    titleTv!!.text = msg.obj as String
                }
                else -> {}
            }
        }
    }
    private var videostreamPreviewTtView: TextureView? = null
    private var videostreamPreviewSf: SurfaceView? = null
    private var videostreamPreviewSh: SurfaceHolder? = null

    private lateinit var surfaceHolder: SurfaceHolder

    private var mCamera: Camera? = null
    private var mCodecManager: DJICodecManager? = null
    private var savePath: TextView? = null
    private var screenShot: Button? = null
    private var stringBuilder: StringBuilder? = null
    private var videoViewWidth = 0
    private var videoViewHeight = 0
    private var count = 0

    private var module = Module.load(assetFilePath(this, "your_model.pt"))

    override fun onResume() {
        super.onResume()
        initSurfaceOrTextureView()
        notifyStatusChange()
    }

    private fun initSurfaceOrTextureView() {
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
        if (mCamera != null) {
            VideoFeeder.getInstance().primaryVideoFeed
                .removeVideoDataListener(mReceivedVideoDataListener)
            standardVideoFeeder?.removeVideoDataListener(mReceivedVideoDataListener)
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (mCodecManager != null) {
            mCodecManager!!.cleanSurface()
            mCodecManager!!.destroyCodec()
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initUi()
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
        mainHandler.sendMessage(
            mainHandler.obtainMessage(MSG_WHAT_UPDATE_TITLE, s)
        )
    }

    private fun initUi() {
        savePath = findViewById<View>(R.id.activity_main_save_path) as TextView
        screenShot = findViewById<View>(R.id.activity_main_screen_shot) as Button
        screenShot!!.isSelected = false
        titleTv = findViewById<View>(R.id.title_tv) as TextView
        videostreamPreviewTtView = findViewById<View>(R.id.livestream_preview_ttv) as TextureView
        videostreamPreviewSf = findViewById<View>(R.id.livestream_preview_sf) as SurfaceView


        videostreamPreviewSf!!.isClickable = true
        videostreamPreviewSf!!.setOnClickListener {
            val rate: Float = VideoFeeder.getInstance().transcodingDataRate
            showToast("current rate:" + rate + "Mbps")
            if (rate < 10) {
                VideoFeeder.getInstance().transcodingDataRate = 10.0f
                showToast("set rate to 10Mbps")
            } else {
                VideoFeeder.getInstance().transcodingDataRate = 3.0f
                showToast("set rate to 3Mbps")
            }
        }
        updateUIVisibility()
    }

    private fun updateUIVisibility() {
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
            VideoFeeder.VideoDataListener { videoBuffer, size ->
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
                    mCodecManager = DJICodecManager(applicationContext, surface, width, height)
                    //For M300RTK, you need to actively request an I frame.
                    mCodecManager!!.resetKeyFrame()
                }
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

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }

    /**
     * Init a surface view for the DJIVideoStreamDecoder
     */
    private fun initPreviewerSurfaceView() {
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

        // Convert the YUV data ByteBuffer to a byte array
        val yuvData = ByteArray(dataSize)
        yuvFrame?.get(yuvData)

        // Create a YuvImage object from the YUV data
        val yuvImage = YuvImage(yuvData, format.getInteger(MediaFormat.KEY_COLOR_FORMAT), width, height, null)

        // Convert the YuvImage to a ByteArrayOutputStream
        val outputStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, outputStream)

        // Convert the ByteArrayOutputStream to a byte array
        val jpegByteArray = outputStream.toByteArray()

        // Create a Bitmap from the byte array
        val bitmap = BitmapFactory.decodeByteArray(jpegByteArray, 0, jpegByteArray.size)

        // Bitmap for further processing or display
        performObjectDetection(bitmap, module)


    }

    fun performObjectDetection(bitmap: Bitmap, model: Module): List<DetectedObject> {
        // Preprocess the input image
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap,
            INPUT_IMAGE_SIZE,
            INPUT_IMAGE_SIZE, false)
        val rotatedBitmap = rotateBitmap(resizedBitmap, 90f) // Adjust rotation if needed
        val normalizedTensor = preprocessImage(rotatedBitmap)

        // Run the inference
        val outputTensor = model.forward(IValue.from(normalizedTensor)).toTensor()

        // Process the output tensor and extract detected objects
        val detectedObjects = processOutputTensor(outputTensor)

        return detectedObjects
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


    fun onClick(v: View) {
        if (v.id == R.id.activity_main_screen_shot) {
            //smth
        } else {
            var newDemoType: DemoType? = null
            if (v.id == R.id.activity_main_screen_texture) {
                newDemoType = DemoType.USE_TEXTURE_VIEW
            } else if (v.id == R.id.activity_main_screen_surface) {
                newDemoType = DemoType.USE_SURFACE_VIEW
            } else if (v.id == R.id.activity_main_screen_surface_with_own_decoder) {
                newDemoType = DemoType.USE_SURFACE_VIEW_DEMO_DECODER
            }
            if (newDemoType != null && newDemoType != demoType) {
                // Although finish will trigger onDestroy() is called, but it is not called before OnCreate of new activity.
                if (mCodecManager != null) {
                    mCodecManager!!.cleanSurface()
                    mCodecManager!!.destroyCodec()
                    mCodecManager = null
                }
                demoType = newDemoType
                finish()
                overridePendingTransition(0, 0)
                startActivity(intent)
                overridePendingTransition(0, 0)
            }
        }
    }

    private val isTranscodedVideoFeedNeeded: Boolean
        get() = if (VideoFeeder.getInstance() == null) {
            false
        } else VideoFeeder.getInstance().isFetchKeyFrameNeeded || VideoFeeder.getInstance()
            .isLensDistortionCalibrationNeeded

    companion object {
        private val TAG = MainActivity::class.java.simpleName
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
}