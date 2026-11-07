package com.iris.lens

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.iris.lens.databinding.ActivityMainBinding
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lastAnalysisAt = 0L
    private var selectedLook = Look.NATURAL
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
    private val faceDetector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST).build())
    }

    private enum class Look { NATURAL, CINEMA, WARM }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val cameraGranted = grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val storageOk = Build.VERSION.SDK_INT >= 29 ||
            grants[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (cameraGranted && storageOk) startCamera()
        else Toast.makeText(this, "Camera and required photo permissions are needed.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupControls()
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= 28) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startCamera() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun setupControls() {
        binding.captureButton.setOnClickListener { takePhoto() }
        binding.naturalButton.setOnClickListener { setLook(Look.NATURAL) }
        binding.cinemaButton.setOnClickListener { setLook(Look.CINEMA) }
        binding.warmButton.setOnClickListener { setLook(Look.WARM) }
        binding.zoomBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val ratio = 1f + progress / 10f
                camera?.cameraControl?.setZoomRatio(ratio)
                binding.zoomValue.text = String.format(Locale.US, "%.1f×", ratio)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        binding.exposureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val state = camera?.cameraInfo?.exposureState ?: return
                val index = progress + state.exposureCompensationRange.lower
                camera?.cameraControl?.setExposureCompensationIndex(index)
                binding.exposureValue.text = String.format(Locale.US, "%+.1f", index * state.exposureCompensationStep.toFloat())
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        setLook(Look.NATURAL)
    }

    private fun setLook(look: Look) {
        selectedLook = look
        binding.lookText.text = "LOOK  •  ${look.name}"
        binding.naturalButton.alpha = if (look == Look.NATURAL) 1f else 0.48f
        binding.cinemaButton.alpha = if (look == Look.CINEMA) 1f else 0.48f
        binding.warmButton.alpha = if (look == Look.WARM) 1f else 0.48f
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, ::analyseFrame) }
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
                val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                if (range != null) {
                    binding.exposureBar.max = (range.upper - range.lower).coerceAtLeast(0)
                    binding.exposureBar.progress = (-range.lower).coerceIn(0, binding.exposureBar.max)
                }
                val zoom = camera?.cameraInfo?.zoomState?.value
                if (zoom != null) binding.zoomBar.max = (((zoom.maxZoomRatio - 1f) * 10f).roundToInt()).coerceAtLeast(0)
                binding.aiStatus.text = "AI ONLINE  •  ON DEVICE"
            } catch (e: Exception) {
                binding.aiStatus.text = "CAMERA ERROR"
                Toast.makeText(this, "Unable to start camera: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyseFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisAt < 1500L) { proxy.close(); return }
        lastAnalysisAt = now
        val mediaImage = proxy.image ?: run { proxy.close(); return }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        val brightness = sampleBrightness(proxy)
        val rotatedWidth = if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.width else proxy.height
        val rotatedHeight = if (proxy.imageInfo.rotationDegrees % 180 == 0) proxy.height else proxy.width
        val isPortraitFrame = rotatedHeight >= rotatedWidth

        val labelTask = labeler.process(input).addOnSuccessListener { labels ->
            val ranked = labels.sortedByDescending { it.confidence }
            val top = ranked.firstOrNull()
            val scene = top?.text ?: "General scene"
            val sceneConfidence = top?.confidence ?: 0f
            val labelSummary = ranked.take(4).joinToString(" · ") { it.text }
            val lower = scene.lowercase(Locale.ROOT)
            val peopleLike = lower.contains("person") || lower.contains("face") || lower.contains("people")
            val landscapeLike = listOf("landscape", "mountain", "sky", "beach", "nature", "sunset", "building").any { lower.contains(it) }
            val brightnessScore = when {
                brightness in 90.0..175.0 -> 25
                brightness in 70.0..205.0 -> 19
                brightness < 45.0 || brightness > 230.0 -> 7
                else -> 13
            }
            val backgroundScore = when {
                ranked.size <= 2 -> 18
                ranked.size <= 4 -> 15
                else -> 10
            }
            val orientationScore = when {
                peopleLike && isPortraitFrame -> 15
                landscapeLike && !isPortraitFrame -> 15
                else -> 9
            }
            val confidenceScore = (sceneConfidence * 15f).roundToInt().coerceIn(4, 15)
            val baseScore = (brightnessScore + backgroundScore + orientationScore + confidenceScore).coerceIn(0, 80)
            val sceneHint = when {
                peopleLike && !isPortraitFrame -> "Try portrait orientation for a person-focused frame."
                landscapeLike && isPortraitFrame -> "Try landscape orientation to give the scene more breathing room."
                else -> "Orientation suits this scene; keep the subject away from the frame edges."
            }
            val lightHint = when {
                brightness < 65 -> "Light is low: move towards a window or brighter light and keep the phone steady."
                brightness > 205 -> "Highlights may be harsh: lower EV slightly and protect bright areas."
                else -> "Light looks workable; keep the main subject clearly separated from the background."
            }
            val backgroundHint = if (ranked.size >= 5) "Background looks visually busy; simplify the edges or move closer to the subject." else "Background appears relatively simple from the detected scene labels."
            val score = (baseScore + if (peopleLike) 12 else 8).coerceIn(0, 100)
            val advice = "$sceneHint $lightHint $backgroundHint"
            runOnUiThread {
                binding.sceneText.text = "SCENE  •  $scene (${(sceneConfidence * 100).roundToInt()}%)"
                binding.scoreText.text = "FRAME SCORE  $score/100  •  ${if (score >= 80) "STRONG" else if (score >= 60) "GOOD START" else "ADJUST"}"
                binding.tipText.text = advice
                binding.backgroundText.text = "BACKGROUND  •  $labelSummary"
            }
        }.addOnFailureListener {
            runOnUiThread { binding.sceneText.text = "SCENE  •  Analysis unavailable" }
        }

        val faceTask = faceDetector.process(input).addOnSuccessListener { faces ->
            if (faces.isNotEmpty()) {
                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                val box = face?.boundingBox
                val centreX = box?.centerX()?.toFloat()?.div(rotatedWidth.coerceAtLeast(1)) ?: 0.5f
                val centreY = box?.centerY()?.toFloat()?.div(rotatedHeight.coerceAtLeast(1)) ?: 0.5f
                val placement = if (centreX in 0.25f..0.75f && centreY in 0.18f..0.58f) "Face placement is balanced." else "Reframe gently: place the eyes around the upper third and leave headroom."
                runOnUiThread {
                    binding.faceText.text = "SUBJECT  •  FACE DETECTED"
                    binding.tipText.text = "$placement Keep the eyes sharp; avoid cutting off the chin or top of the head."
                }
            } else runOnUiThread { binding.faceText.text = "SUBJECT  •  No face detected" }
        }.addOnFailureListener { }
        Tasks.whenAllComplete(labelTask, faceTask).addOnCompleteListener { proxy.close() }
    }

    private fun sampleBrightness(proxy: ImageProxy): Double {
        val plane = proxy.planes.firstOrNull() ?: return 128.0
        val buffer = plane.buffer.duplicate()
        val limit = buffer.limit()
        val step = (limit / 320).coerceAtLeast(1)
        var sum = 0L
        var count = 0
        var index = 0
        while (index < limit) {
            sum += (buffer.get(index).toInt() and 0xFF)
            count++
            index += step
        }
        return if (count == 0) 128.0 else sum.toDouble() / count
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        binding.lastPhoto.text = "CAPTURING…"
        val lookForThisShot = selectedLook
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buffer = image.planes.firstOrNull()?.buffer
                    if (buffer == null) throw IllegalStateException("Image buffer unavailable")
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val options = BitmapFactory.Options().apply {
                        if (lookForThisShot != Look.NATURAL) inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                        ?: throw IllegalStateException("Could not decode captured image")
                    val corrected = applyLook(source, lookForThisShot)
                    saveBitmap(corrected)
                    if (corrected !== source) corrected.recycle()
                    source.recycle()
                    runOnUiThread {
                        binding.lastPhoto.text = "SAVED  •  ${lookForThisShot.name}"
                        Toast.makeText(this@MainActivity, "Photo saved in Pictures / IRIS Lens", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        binding.lastPhoto.text = "SAVE FAILED"
                        Toast.makeText(this@MainActivity, "Capture failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    }
                } finally { image.close() }
            }
            override fun onError(exception: ImageCaptureException) {
                runOnUiThread {
                    binding.lastPhoto.text = "CAPTURE FAILED"
                    Toast.makeText(this@MainActivity, "Capture failed: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun applyLook(source: Bitmap, look: Look): Bitmap {
        if (look == Look.NATURAL) return source
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val matrix = ColorMatrix()
        when (look) {
            Look.CINEMA -> {
                matrix.set(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 2f,
                    0f, 1.02f, 0f, 0f, 1f,
                    0f, 0f, 0.94f, 0f, 3f,
                    0f, 0f, 0f, 1f, 0f
                ))
                matrix.postConcat(ColorMatrix().apply { setSaturation(0.88f) })
            }
            Look.WARM -> matrix.set(floatArrayOf(
                1.08f, 0f, 0f, 0f, 5f,
                0f, 1.02f, 0f, 0f, 2f,
                0f, 0f, 0.91f, 0f, -2f,
                0f, 0f, 0f, 1f, 0f
            ))
            else -> Unit
        }
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun saveBitmap(bitmap: Bitmap) {
        val name = "IRIS_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IRIS Lens")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Gallery could not create a photo entry")
        try {
            val stream = contentResolver.openOutputStream(uri) ?: throw IllegalStateException("Could not open photo output")
            stream.use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) throw IllegalStateException("JPEG encoding failed") }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        labeler.close()
        faceDetector.close()
    }
}
