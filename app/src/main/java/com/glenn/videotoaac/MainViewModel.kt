package com.glenn.videotoaac

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class FileItem(
        val uri: Uri,
        val name: String,
        val phase: Phase = Phase.Idle
    )

    sealed class Phase {
        data object Idle : Phase()
        data object Copying : Phase()     // Copying video to cache
        data object Extracting : Phase()  // Extracting audio track
        data class Loudness(val lufs: Double) : Phase()
        data object Resampling : Phase()   // Resampling PCM
        data class Normalizing(val from: Double, val gainDb: Double) : Phase()
        data object Encoding : Phase()    // Encoding to AAC
        data object Saving : Phase()      // Saving to Downloads
        data object Done : Phase()
        data class Failed(val reason: String) : Phase()
    }

    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    private val _processing = MutableStateFlow(false)
    val processing: StateFlow<Boolean> = _processing.asStateFlow()

    private val _sampleRate = MutableStateFlow("原始")
    val sampleRate: StateFlow<String> = _sampleRate.asStateFlow()

    fun setSampleRate(rate: String) { _sampleRate.value = rate }

    fun addFiles(uris: List<Uri>) {
        val context = getApplication<Application>()
        val newItems = uris.mapNotNull { uri ->
            val name = getFileName(context, uri) ?: uri.lastPathSegment ?: "unknown.mp4"
            if (_files.value.none { it.uri == uri }) FileItem(uri, name) else null
        }
        if (newItems.isNotEmpty()) {
            _files.update { it + newItems }
        }
    }

    fun removeFile(index: Int) {
        _files.update { it.toMutableList().also { list -> list.removeAt(index) } }
    }

    fun clearFiles() { _files.value = emptyList() }

    fun startProcessing() {
        if (_files.value.isEmpty() || _processing.value) return
        _processing.value = true

        val targetRate = if (_sampleRate.value != "原始") _sampleRate.value.toIntOrNull() else null
        val context = getApplication<Application>()

        viewModelScope.launch {
            val list = _files.value.toMutableList()

            for (i in list.indices) {
                val item = list[i]
                val baseName = item.name.removeSuffix(".mp4").removeSuffix(".MP4")
                    .removeSuffix(".mov").removeSuffix(".MOV")
                    .removeSuffix(".mkv").removeSuffix(".MKV")

                try {
                    // Phase 1: Extract + measure
                    updatePhase(i, Phase.Copying)
                    val pcmFile = File(context.cacheDir, "v2a_pcm_$i.pcm")
                    val meta = AudioPipeline.extractAndMeasure(context, item.uri, pcmFile) { msg ->
                        // progress callback just sets phase
                    }
                    updatePhase(i, Phase.Loudness(meta.measuredLufs))

                    // Phase 2: Resample if needed
                    val outRate = targetRate ?: meta.sampleRate
                    val workFile: File
                    if (outRate != meta.sampleRate) {
                        updatePhase(i, Phase.Resampling)
                        val resampledFile = File(context.cacheDir, "v2a_resampled_$i.pcm")
                        AudioPipeline.resamplePcm(pcmFile, resampledFile, meta.sampleRate, outRate, meta.channels)
                        pcmFile.delete()
                        workFile = resampledFile
                    } else {
                        workFile = pcmFile
                    }

                    // Phase 3: Normalize + encode
                    val gainDb = 20.0 * kotlin.math.log10(
                        10.0.pow((-14.0 - meta.measuredLufs) / 20.0)
                    )
                    updatePhase(i, Phase.Normalizing(meta.measuredLufs, gainDb))

                    val aacFile = File(context.cacheDir, "v2a_output_$i.aac")
                    AudioPipeline.normalizeAndEncode(workFile, meta, outRate, aacFile) { }
                    workFile.delete()

                    // Save to Downloads via MediaStore
                    updatePhase(i, Phase.Saving)
                    saveToDownloads(context, baseName, aacFile)
                    aacFile.delete()

                    updatePhase(i, Phase.Done)
                } catch (e: Exception) {
                    updatePhase(i, Phase.Failed(e.message ?: "未知错误"))
                }
            }

            _processing.value = false
        }
    }

    private fun updatePhase(index: Int, phase: Phase) {
        _files.update { list ->
            list.toMutableList().also {
                if (index < it.size) it[index] = it[index].copy(phase = phase)
            }
        }
    }

    private fun saveToDownloads(context: Context, baseName: String, src: File) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${baseName}.aac")
            put(MediaStore.Downloads.MIME_TYPE, "audio/aac")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw RuntimeException("无法写入下载目录")

        context.contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        } ?: throw RuntimeException("无法打开输出流")

        // Notify media scanner
        MediaScannerConnection.scanFile(context, arrayOf(src.absolutePath), null, null)
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
        }
        return name
    }
}
