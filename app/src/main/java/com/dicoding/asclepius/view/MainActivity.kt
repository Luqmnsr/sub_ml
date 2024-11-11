package com.dicoding.asclepius.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dicoding.asclepius.R
import com.dicoding.asclepius.databinding.ActivityMainBinding
import com.dicoding.asclepius.helper.ImageClassifierHelper
import com.yalantis.ucrop.UCrop
import org.tensorflow.lite.task.vision.classifier.Classifications
import java.io.File

class MainActivity : AppCompatActivity(), ImageClassifierHelper.ClassifierListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageClassifierHelper: ImageClassifierHelper
    private var currentImageUri: Uri? = null

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (isGranted) {
                Toast.makeText(this, "Permission request granted", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }

    private fun allPermissionsGranted(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!allPermissionsGranted()) {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestPermissionLauncher.launch(permission)
        }

        imageClassifierHelper = ImageClassifierHelper(
            context = this,
            classifierListener = this
        )

        binding.galleryButton.setOnClickListener { startGallery() }
        binding.analyzeButton.setOnClickListener { analyzeImage() }
    }

    private fun startGallery() {
        currentImageUri = null

        launcherGallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private val launcherGallery = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            currentImageUri = uri
            startCrop(uri)
            showImage()
        } else {
            Log.d("Photo Picker", "No media selected")
        }
    }

    private fun startCrop(imageUri: Uri) {
        val uniqueFileName = "cropped_image_${System.currentTimeMillis()}.jpg"
        val destinationUri = Uri.fromFile(File(this.cacheDir, uniqueFileName))

        val uCropIntent = UCrop.of(imageUri, destinationUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(224, 224)
            .getIntent(this)

        cropImageResultLauncher.launch(uCropIntent)
    }

    private val cropImageResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val resultUri = UCrop.getOutput(result.data!!)
            resultUri?.let {
                currentImageUri = it
                showImage()
            }
        } else {
            val cropError = UCrop.getError(result.data!!)
            cropError?.let {
                showToast(getString(R.string.image_load_failed))
            } ?: showToast("Error cropping image.")
        }
    }

    private fun showImage() {
        if (currentImageUri != null) {
            binding.previewImageView.setImageURI(currentImageUri)
            binding.analyzeButton.visibility = View.VISIBLE
        } else {
            binding.previewImageView.setImageResource(R.drawable.ic_place_holder) // Set placeholder
            binding.analyzeButton.visibility = View.GONE
        }
    }

    private fun analyzeImage() {
        if (currentImageUri != null) {
            binding.progressIndicator.visibility = View.VISIBLE
            imageClassifierHelper.classifyStaticImage(currentImageUri!!)
        } else {
            showToast(getString(R.string.image_classifier_failed))
            Log.e("AnalyzeImage", "Image URI is null.")
        }
    }

    override fun onResults(results: List<Classifications>?, inferenceTime: Long) {
        binding.progressIndicator.visibility = View.GONE
        results?.let {
            // Filter hasil klasifikasi untuk hanya kategori dengan skor > 50%
            val filteredResultText = it.joinToString("\n") { classification ->
                classification.categories
                    .filter { category -> category.score > 0.5 }  // Menyaring hanya kategori dengan skor > 50%
                    .joinToString { category -> "${category.label}: ${category.score * 100}%" }
            }.trim()  // Menghapus baris kosong yang mungkin muncul jika tidak ada kategori di atas 50%

            // Log hasil yang difilter
            Log.d("Filtered Classification Result", filteredResultText)

            // Menampilkan pesan berdasarkan hasil yang difilter
            if (filteredResultText.isNotEmpty()) {
                // Mencari kategori dengan skor tertinggi di antara hasil yang difilter
                val highestCategory = it.flatMap { classification ->
                    classification.categories
                }.maxByOrNull { category -> category.score }

                highestCategory?.let { category ->
                    if (category.score > 0.5) {
                        if (category.label.equals("Cancer", ignoreCase = true)) {
                            val message = "Ini adalah contoh kanker kulit."
                            showToast(message)
                            moveToResult("$filteredResultText\n\n$message")
                        } else if (category.label.equals("Non Cancer", ignoreCase = true)) {
                            val message = "Ini bukan kanker kulit."
                            showToast(message)
                            moveToResult("$filteredResultText\n\n$message")
                        } else {
                            // Jika label tidak sesuai dengan "cancer" atau "non cancer", tampilkan pesan ketidakpastian
                            val message = "Label tidak dikenali, hasil tidak dapat dipastikan."
                            showToast(message)
                            moveToResult("$filteredResultText\n\n$message")
                        }
                    } else {
                        // Jika skor kategori tertinggi kurang dari 50%
                        val message = "Tidak ada kategori dengan persentase > 50% yang terdeteksi."
                        showToast(message)
                        moveToResult("$filteredResultText\n\n$message")
                    }
                }
            }
        } ?: showToast("No classification results")
    }

    override fun onError(error: String) {
        binding.progressIndicator.visibility = View.GONE
        showToast(error)
    }

    private fun moveToResult(result: String) {
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_IMAGE_URI, currentImageUri.toString())
            putExtra(ResultActivity.EXTRA_RESULT, result)
        }
        startActivity(intent)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}