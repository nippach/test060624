package agency.galt.ml_passport_detector

import android.graphics.*
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.Trace
import android.util.Log
import android.util.Size
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.rotationMatrix
import androidx.core.graphics.scaleMatrix
import androidx.core.graphics.times
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import kotlinx.parcelize.Parcelize
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.classifier.ImageClassifier
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

class DetectFragment : Fragment() {
    private lateinit var executor: ExecutorService
    private val selectedImages: MutableList<SelectedImage> = mutableListOf()
    private val framesLog: ArrayDeque<Pair<ComparableTimeMark, String>> = ArrayDeque()
    private var finished: Boolean = false

    private var requestDoneKey: String = "ml_passport_detector_request_done_key"
    private var requestCloseKey: String = "ml_passport_detector_request_close_key"
    private var certainty: Float = 0.5f
    private var framesNumber: Int = 1
    private var debugOverlay: Boolean = false

    private lateinit var labelsText: TextView
    private lateinit var timesText: TextView
    private lateinit var previewView: PreviewView
    private lateinit var frameView: FrameView
    private lateinit var frameText: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var spinnerLayout: ConstraintLayout

    private lateinit var imageCapture: ImageCapture
    private lateinit var classifier: ImageClassifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            requestDoneKey = it.getString(ARG_REQUEST_DONE_KEY, requestDoneKey)
            requestCloseKey = it.getString(ARG_REQUEST_CLOSE_KEY, requestCloseKey)
            certainty = it.getFloat(ARG_CERTAINTY, certainty)
            debugOverlay = it.getBoolean(ARG_DEBUG_OVERLAY, debugOverlay)
        }

        executor = Executors.newSingleThreadExecutor()

        // Init model in executor so that we do it in background
        // plus able to switch to tflite, it requires executing from the same thread as init
        // mlkit creates it's own executor so it's not necessary for it
        executor.submit {
            val initTime = measureNanoTime {
                // FIXME: Is it ok to use context here?
                classifier = try {
                    ImageClassifier.createFromFileAndOptions(
                        context,
                        "iqa_mod.tflite",
                        ImageClassifier.ImageClassifierOptions.builder()
                            .setBaseOptions(BaseOptions.builder().useGpu().setNumThreads(4).build())
                            .build()
                    )
                } catch (e: java.lang.IllegalStateException) {
                    // Try again if there was some error with GPU/NNAPI
                    ImageClassifier.createFromFileAndOptions(
                        context,
                        "iqa_mod.tflite",
                        ImageClassifier.ImageClassifierOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
                            .build(),
                    )
                }
            }
            Log.d(TAG, "Classifier init took ${initTime.toDouble() / 1.0e9}s")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_detect, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val debugVisibility = if (debugOverlay) View.VISIBLE else View.INVISIBLE

        labelsText = view.findViewById(R.id.labels_text)
        timesText = view.findViewById(R.id.times_text)
        previewView = view.findViewById(R.id.preview_view)
        frameView = view.findViewById(R.id.frame_view)
        frameText = view.findViewById(R.id.frame_text)
        closeButton = view.findViewById(R.id.close_button)
        spinnerLayout = view.findViewById(R.id.spinner_layout)

        listOf(labelsText, timesText).map { it.visibility = debugVisibility }
        frameText.text = getString(R.string.place_in_the_frame)
        frameView.frameColor = Color.WHITE
        frameView.invalidate()

        closeButton.setOnClickListener {
            finished = true
            parentFragmentManager.setFragmentResult(requestCloseKey, bundleOf(
                RESULT_FRAMES_LOG to framesLog.joinToString("\n") { "${it.first}: ${it.second}" }
            ))
        }

        // CameraX is managing lifecycle events so we just have to start it
        // Also wait till layout to use it's viewport and crop image properly
        previewView.doOnLayout {
            startCamera()
        }
    }

    private fun startCamera() {
        Log.d(TAG, "Starting camera")

        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        val viewPort = previewView.viewPort!!

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // TODO: Maybe select resolution smarter
            // TODO: Only apply to ImageAnalysis
            // - I remember some case when it was switching resolutions every frame
            //   because of mismatch in different UseCase objects. Maybe it was fixed in CameraX
            val selector = ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(
                    Size(800, 800), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            ).build()

            val preview = Preview.Builder().apply {
                setResolutionSelector(selector)
            }.build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder().apply {
                setResolutionSelector(selector)
            }.build().apply {
                setAnalyzer(executor) { image ->
                    image.use {
                        analyzeFrame(it)
                    }
                }
            }

            val capture = ImageCapture.Builder().apply {
                setResolutionSelector(selector)
                setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            }.build().also { imageCapture = it }

            val useCaseGroup = UseCaseGroup.Builder().apply {
                addUseCase(preview)
                addUseCase(analysis)
                addUseCase(capture)
                setViewPort(viewPort)
            }.build()

            try {
                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, useCaseGroup
                )
                camera.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(
                    SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(.5f, .5f),
                    FocusMeteringAction.FLAG_AF,
                ).apply { setAutoCancelDuration(2, TimeUnit.SECONDS)  }.build())
            } catch (exc: Exception) {
                Log.e(TAG, "Camera use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun analyzeFrame(image: ImageProxy) {
        // onImageAnalyzed is called before image.close()
        // So we can check if we have already finished here
        if (finished) {
            return
        }

        lateinit var input: Bitmap
        lateinit var labels: List<Pair<String, Float>>

        Trace.beginSection("analyzeFrame.prepare")
        val prepareTime = measureNanoTime {
            val rect = run {
                val side = minOf(
                    (previewView.width - 32f * resources.displayMetrics.density) / 343f * 472f,
                    (previewView.height - (32f + 120f /* margin for text */) * resources.displayMetrics.density),
                )

                val width = if (image.imageInfo.rotationDegrees.div(90)
                        .mod(2) == 0
                ) image.cropRect.width() else image.cropRect.height()
                val rectSide = side / previewView.width * width
                val offsets = if (image.imageInfo.rotationDegrees.div(90)
                        .mod(2) == 0
                ) Pair(rectSide / 2 / 472f * 343f, rectSide / 2) else Pair(
                    rectSide / 2, rectSide / 2 / 472f * 343f
                )
                Rect(
                    (image.cropRect.exactCenterX() - offsets.first).roundToInt(),
                    (image.cropRect.exactCenterY() - offsets.second).roundToInt(),
                    (image.cropRect.exactCenterX() + offsets.first).roundToInt() + 1,
                    (image.cropRect.exactCenterY() + offsets.second).roundToInt() + 1,
                )
            }
            // TODO: For better performance it is possible to:
            // - Implement custom .toBitmap that will take YUV_420_888 and crop region
            //   - Will need to specify format in ImageAnalysis builder
            // - Pass rotation directly to ML Kit or TensorFlow Lite
            //   - I do not really know if it speeds up but at least it allows only 90º rotations
            //     so probably it is doing fast transpose instead of matrix operations
            //     but maybe bitmap also has such an optimization (common operation btw)
            input = Bitmap.createBitmap(
                image.toBitmap(),
                rect.left,
                rect.top,
                rect.width(),
                rect.height(),
                rotationMatrix(image.imageInfo.rotationDegrees.toFloat())
                    * scaleMatrix(368f / rect.width(), 368f / rect.height()),
                false,
            )
            if (debugOverlay) {
                frameView.preview = Pair(input, 0)
                frameView.postInvalidate()
            }
        }
        Trace.endSection()

        Trace.beginSection("analyzeFrame.inference")
        val inferenceTime = measureNanoTime {
            labels = classifier.classify(TensorImage().apply { load(input) })[0]
                .categories.map { Pair(it.label, it.score) }
        }
        Trace.endSection()

        Trace.beginSection("analyzeFrame.callback")
        val callbackTime = measureNanoTime {
            onImageAnalyzed(image, labels)
        }
        Trace.endSection()

        Log.d(
            TAG,
            "Analyzed frame: size=(${input.width}x${input.height}) times=($prepareTime,$inferenceTime,$callbackTime) labels=($labels)"
        )

        framesLog.addLast(
            Pair(
                TimeSource.Monotonic.markNow(),
                "Analyzed frame: size=(${input.width}x${input.height}) times=($prepareTime,$inferenceTime,$callbackTime) labels=($labels)"
            )
        )
        while (framesLog.first().first < framesLog.last().first - 2.minutes) {
            framesLog.removeFirst()
        }

        if (isAdded) {
            activity?.runOnUiThread {
                labelsText.text =
                    labels.joinToString("\n") { "${it.first} ${"%.5f".format(it.second)}" }

                val times = listOf(
                    "total" to prepareTime + inferenceTime + callbackTime,
                    "prepare" to prepareTime,
                    "inference" to inferenceTime,
                    "callback" to callbackTime,
                ).joinToString("\n") { "${it.first} ${"%.5f".format(it.second.toDouble() * 1e-9)}" }
                val timesExt =
                    "${times}\nimage size ${image.width}x${image.height}@${image.imageInfo.rotationDegrees}º"
                timesText.text = timesExt
            }
        }
    }

    private fun onImageAnalyzed(image: ImageProxy, labels: List<Pair<String, Float>>) {
        // Check that image is enough good
        if (labels.any { it.second > 1f - certainty }) {
            return
        }

        // Save photo to byte array
        val output = ByteArrayOutputStream()
        val matrix = rotationMatrix(image.imageInfo.rotationDegrees.toFloat())
        val bitmap = image.toBitmap()
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .compress(Bitmap.CompressFormat.JPEG, 80, output)
        output.close()

        selectedImages.add(SelectedImage(output.toByteArray(), labels))

        if (isAdded) {
            activity?.runOnUiThread {
                frameText.text = getString(R.string.keep_in_the_frame)
                frameView.frameColor = Color.parseColor("#FFB72A")
                frameView.invalidate()
            }
        }

        if (selectedImages.size >= framesNumber) {
            finished = true
            takePhotoAndFinish()
        } else {
            // Check that this delay is caused by last frame
            val check = selectedImages.size
            Handler(Looper.getMainLooper()).postDelayed({
                if (check == selectedImages.size && isAdded) {
                    frameText.text = getString(R.string.place_in_the_frame)
                    frameView.frameColor = Color.WHITE
                    frameView.invalidate()
                }
            }, 1000)
        }
    }

    private fun takePhotoAndFinish() {
        if (isAdded) {
            activity?.runOnUiThread {
                spinnerLayout.visibility = View.VISIBLE
            }
        }

        val output = ByteArrayOutputStream()
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(output).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    output.close()

                    if (isAdded) {
                        activity?.runOnUiThread {
                            frameText.text = getString(R.string.got_it)
                            frameView.frameColor = Color.parseColor("#ED2939")
                            frameView.invalidate()
                            spinnerLayout.visibility = View.INVISIBLE
                        }

                        parentFragmentManager.setFragmentResult(requestDoneKey, bundleOf(
                            RESULT_FRAMES_LOG to framesLog.joinToString("\n") { "${it.first}: ${it.second}" }
                        ))
                    }
                }

                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    output.close()

                    if (isAdded) {
                        activity?.runOnUiThread {
                            frameText.text = getString(R.string.got_it)
                            frameView.frameColor = Color.parseColor("#34CA49")
                            frameView.invalidate()
                            spinnerLayout.visibility = View.INVISIBLE
                        }

                        parentFragmentManager.setFragmentResult(
                            requestDoneKey, bundleOf(
                                RESULT_FINAL_IMAGE to output.toByteArray(),
                                RESULT_SELECTED_IMAGES to selectedImages,
                                RESULT_FRAMES_LOG to framesLog.joinToString("\n") { "${it.first}: ${it.second}" }
                            )
                        )
                    }
                }
            })
    }

    companion object {
        private const val TAG = "PassportDetectorFragment"

        const val ARG_REQUEST_DONE_KEY = "requestDoneKey"
        const val ARG_REQUEST_CLOSE_KEY = "requestCloseKey"
        const val ARG_CERTAINTY = "certainty"
        const val ARG_DEBUG_OVERLAY = "debugOverlay"

        const val RESULT_FINAL_IMAGE = "finalImage"
        const val RESULT_SELECTED_IMAGES = "selectedImages"
        const val RESULT_FRAMES_LOG = "framesLog"

        @JvmStatic
        fun newInstance(
            requestDoneKey: String,
            requestCloseKey: String,
            certainty: Float = 0.5f,
            debugOverlay: Boolean = false
        ) = DetectFragment().apply {
            arguments = bundleOf(
                ARG_REQUEST_DONE_KEY to requestDoneKey,
                ARG_REQUEST_CLOSE_KEY to requestCloseKey,
                ARG_CERTAINTY to certainty,
                ARG_DEBUG_OVERLAY to debugOverlay
            )
        }
    }

    @Parcelize
    class SelectedImage(val image: ByteArray, val labels: List<Pair<String, Float>>) : Parcelable
}