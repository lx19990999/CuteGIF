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
import me.tasy5kg.cutegif.toolbox.Toolbox.constraintBy
import me.tasy5kg.cutegif.toolbox.Toolbox.keepScreenOn
import me.tasy5kg.cutegif.toolbox.Toolbox.logRed
import me.tasy5kg.cutegif.toolbox.Toolbox.onClick
import me.tasy5kg.cutegif.toolbox.Toolbox.toast
import kotlin.concurrent.thread
import java.io.File
import java.io.FileInputStream

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
        if (isStandardGif(inputPath)) {
          // Already standard GIF, just copy it
          val outputUri = createNewFile(FileTools.FileName(inputPath).nameWithoutExtension, "gif")
          copyFile(inputPath, outputUri, false)
          successCount++
          logRed("GifFix", "File $currentFile/$totalFiles: Already standard GIF, copied")
        } else {
          // Need to convert
          val outputUri = createNewFile(FileTools.FileName(inputPath).nameWithoutExtension, "gif")
          val outputPath = File(cacheDir, "gif_fix_output_${System.currentTimeMillis()}.gif").absolutePath

          // Get video info to determine fps
          val mediaInfo = FFprobeKit.getMediaInformation("-v quiet -show_streams -i \"$inputPath\"")
          val fps = try {
            val videoStream = mediaInfo.mediaInformation?.streams?.firstOrNull { it.type == "video" }
            val fpsStr = videoStream?.averageFrameRate ?: "10/1"
            val parts = fpsStr.split("/")
            if (parts.size == 2) {
              parts[0].toDouble() / parts[1].toDouble()
            } else {
              10.0
            }
          } catch (e: Exception) {
            10.0
          }

          // Convert to standard GIF
          val command = "$FFMPEG_COMMAND_PREFIX_FOR_ALL_AN -i \"$inputPath\" " +
            "-vf \"fps=$fps,scale=iw:ih:flags=lanczos\" " +
            "-c:v gif -y \"$outputPath\""

          logRed("GifFix command", command)
          val result = FFmpegKit.execute(command)

          if (result.returnCode.isValueSuccess) {
            copyFile(outputPath, outputUri, true)
            successCount++
            logRed("GifFix", "File $currentFile/$totalFiles: Converted successfully")
          } else {
            failCount++
            logRed("GifFix", "File $currentFile/$totalFiles: Conversion failed")
          }
        }
      } catch (e: Exception) {
        failCount++
        logRed("GifFix error", e.message ?: "Unknown error")
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
   * Check if file is standard GIF format by reading file header
   * Standard GIF files start with "GIF87a" or "GIF89a"
   */
  private fun isStandardGif(filePath: String): Boolean {
    return try {
      FileInputStream(filePath).use { fis ->
        val header = ByteArray(6)
        if (fis.read(header) == 6) {
          val headerStr = String(header, Charsets.US_ASCII)
          headerStr == "GIF87a" || headerStr == "GIF89a"
        } else {
          false
        }
      }
    } catch (e: Exception) {
      logRed("isStandardGif error", e.message ?: "Unknown error")
      false
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

