package com.example.ml_passport_detector

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import java.io.File


class MainActivity : AppCompatActivity() {
    class DetectPassport : ActivityResultContract<Triple<Boolean, Float, String>, List<String>?>() {
        override fun createIntent(context: Context, input: Triple<Boolean, Float, String>) =
            Intent(context, DetectActivity::class.java).apply {
                putExtra(DetectActivity.EXTRA_DEBUG_OVERLAY, input.first)
                putExtra(DetectActivity.EXTRA_CERTAINTY, input.second)
                putExtra(DetectActivity.EXTRA_DIRECTORY, input.third)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? {
            if (resultCode != Activity.RESULT_OK) {
                return null
            }
            return intent?.getStringArrayExtra(DetectActivity.EXTRA_IMAGES)?.asList()
//            return intent?.getStringArrayExtra(NewDetectActivity.EXTRA_SELECTED_IMAGES)?.map {
//                val bytes = File(it).readBytes()
//                File(it).delete()
//                bytes
//            }
        }
    }

    class ImageFragment(private val path: String? = null) : Fragment() {
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            return inflater.inflate(R.layout.fragment_image, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            if (path != null) {
                view.findViewById<ImageView>(R.id.image_view).apply {
                    setImageURI(Uri.fromFile(File(path)))
                }
            }
        }
    }


    class ImagesAdapter(fragment: FragmentActivity, private val paths: List<String>) :
        FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = paths.size

        override fun createFragment(position: Int): Fragment {
            return ImageFragment(paths[position])
        }
    }

    private lateinit var tabView: ViewPager2
    private lateinit var mediator: TabLayoutMediator
    private var images: List<String> = listOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ActivityCompat.requestPermissions(
            this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
        )

        val startButton = findViewById<Button>(R.id.start_button)
        val debugSwitch = findViewById<SwitchCompat>(R.id.debug_switch)
        val certaintyBar = findViewById<SeekBar>(R.id.certainty_bar)
        val certaintyText = findViewById<TextView>(R.id.certainty_text)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        tabView = findViewById<ViewPager2>(R.id.tab_view)

        certaintyText.text = "Current certainty: %1.2f".format(certaintyBar.progress * 0.01f)
        tabView.adapter = ImagesAdapter(this, arrayListOf())
        mediator = TabLayoutMediator(tabLayout, tabView) { tab, position ->
            tab.text = "IMAGE ${(position + 1)}"
        }

        val getContent = registerForActivityResult(DetectPassport()) { images ->
            Log.d(TAG, "Got images: ${images?.size}")
            showImages(images)
        }

        certaintyBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(_seekBar: SeekBar?, progress: Int, _fromUser: Boolean) {
                certaintyText.text = "Current certainty: %1.2f".format(progress * 0.01f)
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        startButton.setOnClickListener {
            Log.d(TAG, "Starting scanning activity...")
            getContent.launch(
                Triple(
                    debugSwitch.isChecked, certaintyBar.progress * 0.01f, this.cacheDir.absolutePath
                )
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray("imagesList", images.toTypedArray())
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        showImages(savedInstanceState.getStringArray("imagesList")?.asList())
    }

    private fun showImages(_images: List<String>?) {
        images = _images ?: listOf()
        tabView.adapter = ImagesAdapter(this, images)
        mediator.apply {
            detach()
            attach()
        }
    }

    companion object {
        private const val TAG = "PassportDetectorExample"

        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA
        )
    }
}