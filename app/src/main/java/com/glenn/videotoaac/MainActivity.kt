package com.glenn.videotoaac

import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                VideoToAACApp()
            }
        }
    }
}

data class FileItem(
    val uri: Uri,
    val name: String,
    val status: String = "等待中"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoToAACApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedFiles by remember { mutableStateOf(listOf<FileItem>()) }
    var sampleRate by remember { mutableStateOf("原始") }
    var isProcessing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val sampleRateOptions = listOf("原始", "48000", "44100", "22050", "16000")

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val name = getFileName(context, uri) ?: uri.lastPathSegment ?: "unknown.mp4"
            if (selectedFiles.none { it.uri == uri }) {
                selectedFiles = selectedFiles + FileItem(uri, name)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Video to AAC") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sample rate selector
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { if (!isProcessing) expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = "采样率: $sampleRate",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    sampleRateOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                sampleRate = option
                                expanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { launcher.launch(arrayOf("video/mp4")) },
                    enabled = !isProcessing
                ) {
                    Text("选择视频")
                }
                OutlinedButton(
                    onClick = { selectedFiles = emptyList() },
                    enabled = selectedFiles.isNotEmpty() && !isProcessing
                ) {
                    Text("清空列表")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Process button
            Button(
                onClick = {
                    if (selectedFiles.isEmpty()) {
                        Toast.makeText(context, "请先选择视频文件", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    isProcessing = true
                    scope.launch {
                        processFiles(context, selectedFiles, sampleRate) { updated ->
                            selectedFiles = updated
                        }
                        isProcessing = false
                    }
                },
                enabled = selectedFiles.isNotEmpty() && !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isProcessing) "处理中..." else "开始处理 (${selectedFiles.size} 个文件)",
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Progress
            if (isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Stats
            val doneCount = selectedFiles.count { it.status == "完成" }
            val failCount = selectedFiles.count { it.status.startsWith("失败") }
            if (selectedFiles.isNotEmpty()) {
                Text(
                    text = "完成 $doneCount / ${selectedFiles.size}" +
                            (if (failCount > 0) "  失败 $failCount" else ""),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // File list
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(selectedFiles) { index, file ->
                    FileCard(
                        file = file,
                        onRemove = if (!isProcessing && file.status != "完成") {
                            { selectedFiles = selectedFiles.toMutableList().also { it.removeAt(index) } }
                        } else null
                    )
                }
            }
        }
    }
}

@Composable
fun FileCard(file: FileItem, onRemove: (() -> Unit)?) {
    val statusColor = when {
        file.status == "完成" -> MaterialTheme.colorScheme.primary
        file.status.startsWith("失败") -> MaterialTheme.colorScheme.error
        file.status == "等待中" -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = file.status,
                    color = statusColor,
                    fontSize = 13.sp
                )
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "移除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

private fun getFileName(context: android.content.Context, uri: Uri): String? {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(idx)
        }
    }
    return name
}

private suspend fun processFiles(
    context: android.content.Context,
    files: List<FileItem>,
    sampleRate: String,
    onUpdate: (List<FileItem>) -> Unit
) = withContext(Dispatchers.IO) {
    val list = files.toMutableList()

    for (i in list.indices) {
        // Step 1: Copy file to cache
        list[i] = list[i].copy(status = "复制文件中...")
        withContext(Dispatchers.Main) { onUpdate(list.toList()) }

        val baseName = list[i].name
            .removeSuffix(".mp4").removeSuffix(".MP4")
            .removeSuffix(".mov").removeSuffix(".MOV")
            .removeSuffix(".mkv").removeSuffix(".MKV")
        val tempInput = File(context.cacheDir, "v2a_input_${System.nanoTime()}.mp4")

        try {
            context.contentResolver.openInputStream(list[i].uri)?.use { input ->
                tempInput.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                list[i] = list[i].copy(status = "失败: 无法读取文件")
                withContext(Dispatchers.Main) { onUpdate(list.toList()) }
                continue
            }
        } catch (e: Exception) {
            list[i] = list[i].copy(status = "失败: ${e.message?.take(20)}")
            withContext(Dispatchers.Main) { onUpdate(list.toList()) }
            continue
        }

        // Step 2: Run ffmpeg
        list[i] = list[i].copy(status = "提取+转码中...")
        withContext(Dispatchers.Main) { onUpdate(list.toList()) }

        val tempOutput = File(context.cacheDir, "${baseName}.aac")
        tempOutput.delete()

        val arPart = if (sampleRate != "原始") " -ar $sampleRate" else ""
        val cmd = "-y -i ${tempInput.absolutePath} -vn -c:a aac -b:a 128k -af loudnorm=I=-14:LRA=11:TP=-1$arPart ${tempOutput.absolutePath}"

        val session = FFmpegKit.execute(cmd)
        val returnCode = session.returnCode

        // Clean temp input
        tempInput.delete()

        if (!ReturnCode.isSuccess(returnCode)) {
            val log = session.allLogsAsString
            val shortError = if (log.length > 60) log.takeLast(60) else log
            list[i] = list[i].copy(status = "失败: ${shortError.take(40)}")
            tempOutput.delete()
            withContext(Dispatchers.Main) { onUpdate(list.toList()) }
            continue
        }

        // Step 3: Save to Downloads
        list[i] = list[i].copy(status = "保存到下载...")
        withContext(Dispatchers.Main) { onUpdate(list.toList()) }

        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "${baseName}.aac")
            put(MediaStore.Downloads.MIME_TYPE, "audio/aac")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        try {
            val resolver = context.contentResolver
            val outputUri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (outputUri != null) {
                resolver.openOutputStream(outputUri)?.use { out ->
                    tempOutput.inputStream().use { inp ->
                        inp.copyTo(out)
                    }
                }
                list[i] = list[i].copy(status = "完成")
            } else {
                list[i] = list[i].copy(status = "失败: 无法创建输出文件")
            }
        } catch (e: Exception) {
            list[i] = list[i].copy(status = "失败: ${e.message?.take(20)}")
        }

        tempOutput.delete()
        withContext(Dispatchers.Main) { onUpdate(list.toList()) }
    }
}
