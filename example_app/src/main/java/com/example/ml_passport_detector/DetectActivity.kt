package com.example.ml_passport_detector

import agency.galt.ml_passport_detector.DetectFragment
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.commit
import java.io.File

class DetectActivity : AppCompatActivity() {
    private var debugOverlay: Boolean = false
    private var certainty: Float = 0.5f
    private lateinit var directory: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detect)

        certainty = intent.getFloatExtra(EXTRA_CERTAINTY, certainty)
        debugOverlay = intent.getBooleanExtra(EXTRA_DEBUG_OVERLAY, debugOverlay)
        directory = intent.getStringExtra(EXTRA_DIRECTORY)!!

        val requestDoneKey = "ml_passport_detector_example_request_done_key"
        val requestCloseKey = "ml_passport_detector_example_request_close_key"

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                setReorderingAllowed(true)
                add(
                    R.id.fragment_container_view,
                    DetectFragment.newInstance(requestDoneKey, requestCloseKey, certainty, debugOverlay)
                )
            }
        }

        supportFragmentManager.setFragmentResultListener(requestDoneKey, this) { _, bundle ->
            Log.d(TAG, "${bundle.getString(DetectFragment.RESULT_FRAMES_LOG)}")

            val image = bundle.getByteArray(DetectFragment.RESULT_FINAL_IMAGE)
            val labels = bundle.getParcelableArrayList<DetectFragment.SelectedImage>(DetectFragment.RESULT_SELECTED_IMAGES)?.last()?.labels
            if (image != null) {
                val uuid = java.util.UUID.randomUUID().toString()
                Log.d(TAG, "Saving image $directory $uuid.jpg")

                val path = File(directory, "$uuid.jpg").apply {
                    writeBytes(image)
                    deleteOnExit()
                }.absolutePath

                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_IMAGES, arrayOf(path))
                })
            } else {
                println("Scanning error occurred")
                setResult(RESULT_CANCELED)
            }

            Handler(Looper.getMainLooper()).postDelayed({
                Toast.makeText(this, "labels: $labels", Toast.LENGTH_LONG).show()
                finish()
            }, 500)
        }

        supportFragmentManager.setFragmentResultListener(requestCloseKey, this) { _, bundle ->
            Log.d(TAG, "${bundle.getString(DetectFragment.RESULT_FRAMES_LOG)}")

            println("Scanning was closed")
            finish()
        }
    }

    companion object {
        private const val TAG = "PassportDetectorActivity"

        const val EXTRA_CERTAINTY = "EXTRA_CERTAINTY"
        const val EXTRA_DEBUG_OVERLAY = "EXTRA_DEBUG_OVERLAY"
        const val EXTRA_DIRECTORY = "EXTRA_DIRECTORY"
        const val EXTRA_IMAGES = "EXTRA_IMAGES"
    }
}