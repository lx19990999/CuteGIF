package me.tasy5kg.cutegif

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import me.tasy5kg.cutegif.MyConstants.FFMPEG_COMMAND_PREFIX_FOR_ALL_AN
import me.tasy5kg.cutegif.databinding.ActivityGifFixBinding
import me.tasy5kg.cutegif.toolbox.FileTools
import me.tasy5kg.cutegif.toolbox.FileTools.copyFile
import me.tasy5kg.cutegif.toolbox.FileTools.createNewFile
import me.tasy5kg.cutegif.toolbox.MediaTools
import me.tasy5kg.cutegif.toolbox.Toolbox.constraintBy
import me.tasy5kg.cutegif.toolbox.Toolbox.keepScreenOn
import me.tasy5kg.cutegif.toolbox.Toolbox.logRed
import me.tasy5kg.cutegif.toolbox.Toolbox.onClick
import me.tasy5kg.cutegif.toolbox.Toolbox.toast
import kotlin.concurrent.thread
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import android.graphics.ImageDecoder
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GifFixActivity : BaseActivity() {
  private val binding by lazy { ActivityGifFixBinding.inflate(layoutInflater) }
  private val inputFilePaths by lazy { 
    intent.getStringArrayListExtra(EXTRA_GIF_PATHS) ?: emptyList()
  }
  private var taskThread: Thread? = null
  private var taskQuitOrFailed = false

  override fun onCreateIfEulaAccepted(savedInstanceState: Bundle?) {
    setContentView(binding.root)
    setFinishOnTouchOutside(false)
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        quitOrFailed(getString(R.string.cancelled))
      }
    })
    binding.mbClose.onClick {
      quitOrFailed(getString(R.string.cancelled))
    }
    if (inputFilePaths.isEmpty()) {
      toast(R.string.no_files_selected)
      finish()
      return
    }
    taskThread = thread { performFix() }
  }

  private fun performFix() {
    keepScreenOn(true)
    val totalFiles = inputFilePaths.size
    var successCount = 0
    var failCount = 0

    inputFilePaths.forEachIndexed { index, inputPath ->
      if (taskQuitOrFailed) return@forEachIndexed

      val currentFile = index + 1
      runOnUiThread {
        binding.mtvTitle.text = getString(R.string.processing_file_d_of_d, currentFile, totalFiles)
        binding.linearProgressIndicator.setProgress(
          (currentFile * 100 / totalFiles).constraintBy(0..99), true
        )
      }

      try {
        logRed("GifFix", "File $currentFile/$totalFiles: Starting processing for: $inputPath")
        
        // Check if file exists
        val inputFile = File(inputPath)
        logRed("GifFix", "File $currentFile/$totalFiles: File exists: ${inputFile.exists()}, readable: ${inputFile.canRead()}, size: ${if (inputFile.exists()) inputFile.length() else 0} bytes")
        
        if (!inputFile.exists()) {
          failCount++
          logRed("GifFix error", "File $currentFile/$totalFiles: Input file does not exist: $inputPath")
          return@forEachIndexed
        }
        if (!inputFile.canRead()) {
          failCount++
          logRed("GifFix error", "File $currentFile/$totalFiles: Cannot read input file: $inputPath")
          return@forEachIndexed
        }
        if (inputFile.length() == 0L) {
          failCount++
          logRed("GifFix error", "File $currentFile/$totalFiles: Input file is empty: $inputPath")
          return@forEachIndexed
        }
        
        val fileFormat = detectFileFormat(inputPath)
        logRed("GifFix", "File $currentFile/$totalFiles: Detected format: ${fileFormat.name}, size: ${inputFile.length()} bytes")
        logRed("GifFix", "File $currentFile/$totalFiles: File format enum: $fileFormat")
        
        when (fileFormat) {
          FileFormat.STANDARD_GIF -> {
            // Already standard GIF, just copy it
            val outputUri = createNewFile(FileTools.FileName(inputPath).nameWithoutExtension, "gif")
            copyFile(inputPath, outputUri, false)
            successCount++
            logRed("GifFix", "File $currentFile/$totalFiles: Already standard GIF, copied")
          }
          FileFormat.HEIF, FileFormat.WEBP, FileFormat.OTHER -> {
            // Need to convert to standard GIF
            val outputUri = createNewFile(FileTools.FileName(inputPath).nameWithoutExtension, "gif")
            val outputPath = File(cacheDir, "gif_fix_output_${System.currentTimeMillis()}.gif").absolutePath

            // Get video info to determine fps
            logRed("GifFix", "File $currentFile/$totalFiles: Getting media info for $inputPath (exists: ${inputFile.exists()}, readable: ${inputFile.canRead()})")
            // Use MediaTools.mediaInformation which is used elsewhere in the codebase
            val mediaInfo = MediaTools.mediaInformation(inputPath)
            if (mediaInfo == null) {
              failCount++
              logRed("GifFix FFprobe error", "File $currentFile/$totalFiles: Failed to get media information")
              return@forEachIndexed
            }
            
            val fps = try {
              val videoStream = mediaInfo.streams.firstOrNull { it.type == "video" }
              val fpsStr = videoStream?.averageFrameRate ?: "10/1"
              logRed("GifFix", "File $currentFile/$totalFiles: Detected fps: $fpsStr")
              val parts = fpsStr.split("/")
              if (parts.size == 2) {
                parts[0].toDouble() / parts[1].toDouble()
              } else {
                10.0
              }
            } catch (e: Exception) {
              logRed("GifFix fps error", "File $currentFile/$totalFiles: Failed to parse fps: ${e.message}")
              10.0
            }

            // Convert to standard GIF
            // Use absolute path directly (Android paths don't need escaping like Windows)
            val inputPathForFFmpeg = inputFile.absolutePath
            val outputPathForFFmpeg = File(outputPath).absolutePath
            logRed("GifFix", "File $currentFile/$totalFiles: FFmpeg input path: $inputPathForFFmpeg")
            logRed("GifFix", "File $currentFile/$totalFiles: FFmpeg output path: $outputPathForFFmpeg")
            
            // For WebP animation, FFmpeg's webp decoder doesn't support animation chunks
            // Use Android ImageDecoder to extract frames, then convert to GIF
            val command = when (fileFormat) {
              FileFormat.WEBP -> {
                // For WebP animation, FFmpeg's webp decoder doesn't support animation chunks
                // Use Android ImageDecoder to extract frames, then convert to GIF using FFmpeg
                // Double-check file format to ensure it's actually WebP
                val actualFormat = detectFileFormat(inputPathForFFmpeg)
                if (actualFormat != FileFormat.WEBP) {
                  logRed("GifFix WebP", "File $currentFile/$totalFiles: File format mismatch, detected as ${actualFormat.name}, falling back to FFmpeg")
                  // Fall back to FFmpeg direct conversion
                  "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -i \"$inputPathForFFmpeg\" " +
                    "-vf \"fps=$fps,scale=iw:ih:flags=lanczos\" " +
                    "-c:v gif -y \"$outputPathForFFmpeg\""
                } else {
                  val framesDir = File(cacheDir, "webp_frames_${System.currentTimeMillis()}")
                  framesDir.mkdirs()
                  
                  try {
                    logRed("GifFix WebP", "File $currentFile/$totalFiles: Extracting frames from WebP using ImageDecoder")
                    
                    // Use ImageDecoder to decode animated WebP
                    val source = ImageDecoder.createSource(File(inputPathForFFmpeg))
                  val drawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                  } else {
                    ImageDecoder.decodeDrawable(source)
                  }
                  
                  val command = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && drawable is AnimatedImageDrawable) {
                    // Extract frames from animated WebP using periodic sampling
                    // Must run on main thread for AnimatedImageDrawable callbacks
                    try {
                      val extractedFrames = mutableListOf<Bitmap>()
                      val frameDurations = mutableListOf<Int>()
                      val handler = Handler(Looper.getMainLooper())
                      val latch = CountDownLatch(1)
                      var currentFrame = 0
                      val maxFrames = 1000 // Safety limit
                      var lastFrameTime = System.currentTimeMillis()
                      val sampleInterval = 33L // Sample at ~30fps (33ms per frame)
                      var animationEnded = false
                      var callbackRegistered = false
                      
                      val callback = object : android.graphics.drawable.Animatable2.AnimationCallback() {
                        override fun onAnimationStart(drawable: Drawable?) {
                          logRed("GifFix WebP", "File $currentFile/$totalFiles: Animation started")
                        }
                        
                        override fun onAnimationEnd(drawable: Drawable?) {
                          logRed("GifFix WebP", "File $currentFile/$totalFiles: Animation ended, extracted ${extractedFrames.size} frames")
                          animationEnded = true
                          if (latch.count > 0) {
                            latch.countDown()
                          }
                        }
                      }
                      
                      val frameExtractor = object : Runnable {
                        override fun run() {
                          if (currentFrame < maxFrames && !animationEnded) {
                            try {
                              val now = System.currentTimeMillis()
                              val duration = (now - lastFrameTime).toInt()
                              frameDurations.add(duration)
                              lastFrameTime = now
                              
                              val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                              val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                              val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                              // Clear bitmap to transparent to preserve alpha channel
                              bitmap.eraseColor(Color.TRANSPARENT)
                              val canvas = Canvas(bitmap)
                              // Use Paint with anti-aliasing and preserve alpha
                              val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                              paint.isAntiAlias = true
                              drawable.setBounds(0, 0, width, height)
                              drawable.draw(canvas)
                              extractedFrames.add(bitmap)
                              currentFrame++
                              
                              handler.postDelayed(this, sampleInterval)
                            } catch (e: Exception) {
                              logRed("GifFix WebP", "File $currentFile/$totalFiles: Error extracting frame: ${e.message}")
                              animationEnded = true
                              if (latch.count > 0) {
                                latch.countDown()
                              }
                            }
                          } else {
                            if (!animationEnded) {
                              drawable.stop()
                            }
                            if (latch.count > 0) {
                              latch.countDown()
                            }
                          }
                        }
                      }
                      
                      // Register callback and start animation on main thread synchronously
                      val setupLatch = CountDownLatch(1)
                      runOnUiThread {
                        try {
                          drawable.registerAnimationCallback(callback)
                          callbackRegistered = true
                          drawable.start()
                          handler.post(frameExtractor)
                        } catch (e: Exception) {
                          logRed("GifFix WebP error", "File $currentFile/$totalFiles: Failed to setup animation: ${e.message}")
                          if (latch.count > 0) {
                            latch.countDown()
                          }
                        } finally {
                          setupLatch.countDown()
                        }
                      }
                      setupLatch.await(2, TimeUnit.SECONDS)
                      
                      if (!callbackRegistered) {
                        throw IllegalStateException("Failed to register animation callback")
                      }
                      
                      // Wait for animation to complete (with timeout)
                      val timeout = 10L
                      val waited = latch.await(timeout, TimeUnit.SECONDS)
                      
                      // Clean up on main thread
                      val cleanupLatch = CountDownLatch(1)
                      runOnUiThread {
                        try {
                          handler.removeCallbacks(frameExtractor)
                          if (callbackRegistered) {
                            drawable.unregisterAnimationCallback(callback)
                          }
                          if (!animationEnded) {
                            drawable.stop()
                          }
                        } catch (e: Exception) {
                          logRed("GifFix WebP", "File $currentFile/$totalFiles: Error during cleanup: ${e.message}")
                        } finally {
                          cleanupLatch.countDown()
                        }
                      }
                      cleanupLatch.await(1, TimeUnit.SECONDS)
                      
                      if (!waited) {
                        logRed("GifFix WebP", "File $currentFile/$totalFiles: Animation timeout after $timeout seconds, using extracted frames")
                      }
                      
                      val frameCount = extractedFrames.size
                      logRed("GifFix WebP", "File $currentFile/$totalFiles: Extracted $frameCount frames")
                      
                      if (frameCount == 0) {
                        throw IllegalStateException("No frames extracted from WebP")
                      }
                      
                      // Save extracted frames
                      extractedFrames.forEachIndexed { index, bitmap ->
                        val framePath = File(framesDir, String.format("%06d.png", index + 1)).absolutePath
                        with(MediaTools) { bitmap.saveToPng(framePath) }
                      }
                      
                      // Calculate average frame duration for fps
                      val avgDuration = if (frameDurations.isNotEmpty()) {
                        frameDurations.average().toInt()
                      } else {
                        100 // Default 10fps
                      }
                      val calculatedFps = if (avgDuration > 0) 1000.0 / avgDuration else fps
                      
                      logRed("GifFix WebP", "File $currentFile/$totalFiles: Extracted $frameCount frames, calculated fps: $calculatedFps")
                      
                      // Convert frames to GIF using FFmpeg with transparency support
                      // Use palettegen and paletteuse to preserve transparency
                      "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -framerate $calculatedFps -i \"${framesDir.absolutePath}/%06d.png\" " +
                        "-vf \"split[s0][s1];[s0]palettegen=reserve_transparent=1[p];[s1][p]paletteuse\" " +
                        "-c:v gif -y \"$outputPathForFFmpeg\""
                    } catch (e: Exception) {
                      logRed("GifFix WebP error", "File $currentFile/$totalFiles: Failed to extract frames: ${e.message}\n${e.stackTraceToString()}")
                      failCount++
                      return@forEachIndexed
                    }
                  } else {
                    // Not animated, treat as single frame
                    logRed("GifFix WebP", "File $currentFile/$totalFiles: WebP is not animated, converting as single frame")
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    drawable.setBounds(0, 0, width, height)
                    drawable.draw(canvas)
                    val framePath = File(framesDir, "000001.png").absolutePath
                    with(MediaTools) { bitmap.saveToPng(framePath) }
                    
                    "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -loop 1 -framerate $fps -i \"$framePath\" " +
                      "-vf \"scale=iw:ih:flags=lanczos\" " +
                      "-c:v gif -y \"$outputPathForFFmpeg\""
                  }
                  
                    command
                  } catch (e: Exception) {
                    logRed("GifFix WebP error", "File $currentFile/$totalFiles: Failed to extract frames: ${e.message}\n${e.stackTraceToString()}")
                    // Fall back to FFmpeg direct conversion instead of failing
                    logRed("GifFix WebP", "File $currentFile/$totalFiles: Falling back to FFmpeg direct conversion")
                    "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -i \"$inputPathForFFmpeg\" " +
                      "-vf \"fps=$fps,scale=iw:ih:flags=lanczos\" " +
                      "-c:v gif -y \"$outputPathForFFmpeg\""
                  }
                }
              }
              FileFormat.HEIF -> {
                // For HEIF, let FFmpeg auto-detect the format (some FFmpeg builds don't support -f heif)
                // HEIF animation sequences may need special handling
                "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -i \"$inputPathForFFmpeg\" " +
                  "-vf \"fps=$fps,scale=iw:ih:flags=lanczos,split[s0][s1];[s0]palettegen=reserve_transparent=1[p];[s1][p]paletteuse\" " +
                  "-c:v gif -y \"$outputPathForFFmpeg\""
              }
              else -> {
                // For other formats, let FFmpeg auto-detect
                "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -i \"$inputPathForFFmpeg\" " +
                  "-vf \"fps=$fps,scale=iw:ih:flags=lanczos\" " +
                  "-c:v gif -y \"$outputPathForFFmpeg\""
              }
            }

            try {
              logRed("GifFix command", "File $currentFile/$totalFiles: Command generated")
              try {
                val commandPreview = command?.take(200) ?: "null"
                logRed("GifFix command", "File $currentFile/$totalFiles: Command preview: $commandPreview...")
              } catch (e: Exception) {
                logRed("GifFix command", "File $currentFile/$totalFiles: Failed to get command preview: ${e.message}")
              }
              logRed("GifFix", "File $currentFile/$totalFiles: Command length: ${command?.length ?: 0}")
              logRed("GifFix", "File $currentFile/$totalFiles: Command is null: ${command == null}, empty: ${command?.isEmpty()}")
              
              // Check if command is null or empty
              if (command == null || command.isEmpty()) {
                failCount++
                logRed("GifFix error", "File $currentFile/$totalFiles: Command is null or empty for ${fileFormat.name}")
                return@forEachIndexed
              }
              
              logRed("GifFix", "File $currentFile/$totalFiles: Command validation passed, proceeding to execute")
              
              // Use CountDownLatch to wait for async execution
              val latch = java.util.concurrent.CountDownLatch(1)
              var conversionSuccess = false
              
              logRed("GifFix", "File $currentFile/$totalFiles: Starting FFmpeg execution for ${fileFormat.name}")
              logRed("GifFix", "File $currentFile/$totalFiles: About to call FFmpegKit.executeAsync")
              
              var ffmpegStarted = false
              FFmpegKit.executeAsync(command, { session ->
                ffmpegStarted = true
                logRed("GifFix", "File $currentFile/$totalFiles: FFmpeg session callback triggered")
              val allOutput = session.allLogsAsString
              val returnCode = session.returnCode
              logRed("GifFix FFmpeg output", "File $currentFile/$totalFiles:\nReturn code: $returnCode\nIs success: ${returnCode.isValueSuccess}\nOutput length: ${allOutput.length}\nOutput: $allOutput")
              
              if (returnCode.isValueSuccess) {
                // Check if output file exists
                val outputFile = File(outputPath)
                logRed("GifFix", "File $currentFile/$totalFiles: Checking output file: $outputPath, exists: ${outputFile.exists()}, size: ${if (outputFile.exists()) outputFile.length() else 0}")
                if (outputFile.exists() && outputFile.length() > 0) {
                  copyFile(outputPath, outputUri, true)
                  conversionSuccess = true
                  logRed("GifFix", "File $currentFile/$totalFiles: Converted from ${fileFormat.name} successfully")
                } else {
                  logRed("GifFix error", "File $currentFile/$totalFiles: Output file not created or empty: $outputPath")
                }
              } else {
                logRed("GifFix error", "File $currentFile/$totalFiles: Conversion from ${fileFormat.name} failed\nReturn code: $returnCode\nOutput: $allOutput")
              }
              latch.countDown()
            }, { logCallback ->
              logRed("GifFix FFmpeg log", "File $currentFile/$totalFiles: ${logCallback.message}")
            }, { _ ->
              // Progress callback - can be used for UI updates if needed
            })
            
              logRed("GifFix", "File $currentFile/$totalFiles: FFmpegKit.executeAsync called, waiting for execution...")
              
              // Wait for conversion with timeout (30 seconds for HEIF, longer for complex formats)
              val timeout = if (fileFormat == FileFormat.HEIF) 60L else 30L
              logRed("GifFix", "File $currentFile/$totalFiles: Waiting for FFmpeg conversion (timeout: ${timeout}s)")
              val startWaitTime = System.currentTimeMillis()
              val waited = latch.await(timeout, java.util.concurrent.TimeUnit.SECONDS)
              val waitDuration = System.currentTimeMillis() - startWaitTime
              logRed("GifFix", "File $currentFile/$totalFiles: Wait completed, waited: ${waitDuration}ms, result: $waited, ffmpegStarted: $ffmpegStarted")
              
              if (!waited) {
                failCount++
                logRed("GifFix error", "File $currentFile/$totalFiles: Conversion timeout after ${timeout}s for ${fileFormat.name}")
                FFmpegKit.cancel()
                // Check if output file was created despite timeout
                val outputFile = File(outputPath)
                if (outputFile.exists() && outputFile.length() > 0) {
                  logRed("GifFix", "File $currentFile/$totalFiles: Output file exists despite timeout, attempting to use it")
                  try {
                    copyFile(outputPath, outputUri, true)
                    conversionSuccess = true
                    successCount++
                    logRed("GifFix", "File $currentFile/$totalFiles: Successfully used output file despite timeout")
                  } catch (e: Exception) {
                    logRed("GifFix error", "File $currentFile/$totalFiles: Failed to copy output file: ${e.message}")
                  }
                }
              } else if (conversionSuccess) {
                successCount++
                logRed("GifFix", "File $currentFile/$totalFiles: Conversion succeeded for ${fileFormat.name}")
              } else {
                failCount++
                logRed("GifFix error", "File $currentFile/$totalFiles: Conversion failed for ${fileFormat.name}")
              }
            } catch (e: Exception) {
              failCount++
              logRed("GifFix error", "File $currentFile/$totalFiles: Exception during FFmpeg execution: ${e.message}\n${e.stackTraceToString()}")
            }
          }
        }
      } catch (e: Exception) {
        failCount++
        val errorMsg = e.message ?: "Unknown error"
        val stackTrace = e.stackTraceToString()
        logRed("GifFix exception", "File $currentFile/$totalFiles: Exception occurred\nError: $errorMsg\nStackTrace: $stackTrace")
        e.printStackTrace()
      }
    }

    if (!taskQuitOrFailed) {
      runOnUiThread {
        binding.linearProgressIndicator.setProgress(100, true)
        binding.mtvTitle.text = getString(R.string.fixing_gif_format)
        if (successCount > 0) {
          toast(getString(R.string.gif_format_fixed))
        }
        if (failCount > 0) {
          toast(getString(R.string.gif_format_fix_failed))
        }
        finish()
      }
    }
  }

  /**
   * File format detection enum
   */
  private enum class FileFormat {
    STANDARD_GIF,  // Standard GIF (GIF87a or GIF89a)
    HEIF,          // HEIF format (ftypmsf1 or ftypheic)
    WEBP,          // WebP format (RIFF...WEBP)
    OTHER          // Other formats that need conversion
  }

  /**
   * Detect file format by reading file header
   */
  private fun detectFileFormat(filePath: String): FileFormat {
    return try {
      FileInputStream(filePath).use { fis ->
        val header = ByteArray(12)
        val bytesRead = fis.read(header)
        if (bytesRead < 6) {
          FileFormat.OTHER
        } else {
          // Check for standard GIF (GIF87a or GIF89a)
          val gifHeader = String(header, 0, 6, Charsets.US_ASCII)
          if (gifHeader == "GIF87a" || gifHeader == "GIF89a") {
            FileFormat.STANDARD_GIF
          } else if (bytesRead >= 12) {
            // Check for WebP format (RIFF...WEBP)
            val riffHeader = String(header, 0, 4, Charsets.US_ASCII)
            val webpHeader = String(header, 8, 4, Charsets.US_ASCII)
            if (riffHeader == "RIFF" && webpHeader == "WEBP") {
              FileFormat.WEBP
            } else {
              // Check for HEIF format (ftypmsf1 or ftypheic)
              val headerStr = String(header, Charsets.US_ASCII)
              if (headerStr.contains("ftypmsf1") || headerStr.contains("ftypheic")) {
                FileFormat.HEIF
              } else {
                // Default to OTHER if format not recognized
                FileFormat.OTHER
              }
            }
          } else {
            FileFormat.OTHER
          }
        }
      }
    } catch (e: Exception) {
      logRed("detectFileFormat error", e.message ?: "Unknown error")
      FileFormat.OTHER
    }
  }

  private fun quitOrFailed(toastText: String?) {
    runOnUiThread {
      taskQuitOrFailed = true
      toastText?.let { toast(it) }
      FFmpegKit.cancel()
      FFmpegKitConfig.clearSessions()
      taskThread?.interrupt()
      finish()
    }
  }

  override fun onDestroy() {
    keepScreenOn(false)
    super.onDestroy()
  }

  companion object {
    const val EXTRA_GIF_PATHS = "EXTRA_GIF_PATHS"

    fun start(context: Context, filePaths: List<String>) {
      context.startActivity(Intent(context, GifFixActivity::class.java).apply {
        putStringArrayListExtra(EXTRA_GIF_PATHS, ArrayList(filePaths))
      })
    }
  }
}

