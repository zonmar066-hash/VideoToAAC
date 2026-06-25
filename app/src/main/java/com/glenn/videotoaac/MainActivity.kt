package com.glenn.videotoaac

import android.os.Bundle
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
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { VideoToAACApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoToAACApp(vm: MainViewModel = viewModel()) {
    val files by vm.files.collectAsState()
    val processing by vm.processing.collectAsState()
    val sampleRate by vm.sampleRate.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> vm.addFiles(uris) }

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
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Sample rate picker
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (!processing) expanded = !expanded }) {
                OutlinedTextField(
                    value = "采样率: $sampleRate",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("原始", "48000", "44100", "22050", "16000").forEach { opt ->
                        DropdownMenuItem(text = { Text(opt) }, onClick = {
                            vm.setSampleRate(opt); expanded = false
                        })
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launcher.launch(arrayOf("video/*")) }, enabled = !processing) {
                    Text("选择视频")
                }
                OutlinedButton(onClick = { vm.clearFiles() },
                    enabled = files.isNotEmpty() && !processing) {
                    Text("清空")
                }
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    if (files.isEmpty()) {
                        Toast.makeText(context, "请先选择视频文件", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    vm.startProcessing()
                },
                enabled = files.isNotEmpty() && !processing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (processing) "处理中…" else "开始处理 (${files.size} 个文件)",
                    fontSize = 16.sp
                )
            }

            if (processing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            // Stats
            val done = files.count { it.phase is MainViewModel.Phase.Done }
            val failed = files.count { it.phase is MainViewModel.Phase.Failed }
            if (files.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "完成 $done / ${files.size}" +
                            (if (failed > 0) "  失败 $failed" else ""),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))

            // File list
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(files) { idx, file ->
                    FileCard(file = file, onRemove = if (!processing && file.phase !is MainViewModel.Phase.Done) {
                        { vm.removeFile(idx) }
                    } else null)
                }
            }
        }
    }
}

@Composable
fun FileCard(file: MainViewModel.FileItem, onRemove: (() -> Unit)?) {
    val statusText = when (val p = file.phase) {
        is MainViewModel.Phase.Idle -> "等待中"
        is MainViewModel.Phase.Copying -> "复制中…"
        is MainViewModel.Phase.Extracting -> "提取音轨…"
        is MainViewModel.Phase.Loudness -> "响度: ${"%.1f".format(p.lufs)} LUFS"
        is MainViewModel.Phase.Resampling -> "重采样…"
        is MainViewModel.Phase.Normalizing -> "归一化: ${"%.1f".format(p.from)} → -14 LUFS"
        is MainViewModel.Phase.Encoding -> "编码 AAC…"
        is MainViewModel.Phase.Saving -> "保存到下载…"
        is MainViewModel.Phase.Done -> "完成 ✓"
        is MainViewModel.Phase.Failed -> "失败: ${p.reason}"
    }

    val statusColor = when (file.phase) {
        is MainViewModel.Phase.Done -> MaterialTheme.colorScheme.primary
        is MainViewModel.Phase.Failed -> MaterialTheme.colorScheme.error
        is MainViewModel.Phase.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.secondary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(file.name, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(statusText, color = statusColor, fontSize = 13.sp)
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, "移除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
