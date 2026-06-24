package com.glenn.videotoaac

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object AudioProcessor {

    suspend fun process(
        context: android.content.Context,
        inputUri: Uri,
        outputFile: File,
        targetSampleRate: Int?,
        onProgress: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val tempInput = File(context.cacheDir, "v2a_input_${System.nanoTime()}.mp4")
        try {
            onProgress("复制文件中...")
            context.contentResolver.openInputStream(inputUri)?.use { input ->
                tempInput.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false

            onProgress("提取音频...")
            val pcmData = extractPcm(tempInput, onProgress) ?: return@withContext false.also { tempInput.delete() }
            tempInput.delete()

            onProgress("响度分析...")
            val sampleRate = pcmData.sampleRate
            val channels = pcmData.channels

            // Measure integrated loudness (ITU-R BS.1770-4 simplified)
            val measuredLufs = measureIntegratedLoudness(pcmData.samples, sampleRate, channels)

            // Calculate gain to reach -14 LUFS
            val gainLinear = 10.0.pow((-14.0 - measuredLufs) / 20.0)
            onProgress("归一化: ${"%.1f".format(measuredLufs)} → -14 LUFS (增益 ${"%.1f".format(20 * log10(gainLinear))} dB)")

            // Apply gain
            val normalized = FloatArray(pcmData.samples.size) {
                (pcmData.samples[it] * gainLinear).coerceIn(-1f, 1f)
            }

            // Resample if needed
            val outRate = targetSampleRate ?: sampleRate
            val finalSamples = if (outRate != sampleRate) {
                onProgress("重采样 ${sampleRate} → ${outRate} Hz...")
                resample(normalized, sampleRate, outRate, channels)
            } else normalized

            // Encode to AAC
            onProgress("编码 AAC...")
            encodeAac(finalSamples, outRate, channels, outputFile)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            tempInput.delete()
            false
        }
    }

    private data class PcmData(
        val samples: FloatArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun extractPcm(inputFile: File, onProgress: (String) -> Unit): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = fmt
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val allSamples = mutableListOf<Float>()
            var inputDone = false
            var outputDone = false

            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Collect output
                val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outIdx < 0 -> continue
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }

                if (bufferInfo.size > 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)!!
                    val shortBuf = ShortArray(bufferInfo.size / 2)
                    outBuf.asShortBuffer().apply { position(bufferInfo.offset / 2) }.get(shortBuf)
                    for (s in shortBuf) {
                        allSamples.add(s / 32768f)
                    }
                }
                decoder.releaseOutputBuffer(outIdx, false)
            }

            decoder.stop()
            decoder.release()
            extractor.release()

            return PcmData(allSamples.toFloatArray(), sampleRate, channels)
        } catch (e: Exception) {
            extractor.release()
            return null
        }
    }

    // ITU-R BS.1770-4 integrated loudness measurement (simplified)
    private fun measureIntegratedLoudness(samples: FloatArray, sampleRate: Int, channels: Int): Double {
        // Resample mono for measurement (average channels)
        val mono = if (channels == 1) samples else {
            FloatArray(samples.size / channels) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += samples[i * channels + c]
                sum / channels
            }
        }

        // K-weighting filter (ITU-R BS.1770-4, 48kHz coefficients)
        // Stage 1: high-pass
        val hp = applyBiquad(mono,
            b0 = 1.0, b1 = -2.0, b2 = 1.0,
            a1 = -1.99004745483398, a2 = 0.99007225036621)
        // Stage 2: high-shelf
        val kw = applyBiquad(hp,
            b0 = 1.53512485958697, b1 = -2.69169618940638, b2 = 1.19839281085285,
            a1 = -1.69065929318241, a2 = 0.73248077421585)

        // 400ms blocks with 75% overlap
        val blockSize = (sampleRate * 0.4).toInt()
        val hopSize = (blockSize / 4)
        val powers = mutableListOf<Double>()

        var pos = 0
        while (pos + blockSize <= kw.size) {
            var sumSq = 0.0
            for (i in pos until pos + blockSize) {
                sumSq += kw[i].toDouble() * kw[i].toDouble()
            }
            powers.add(sumSq / blockSize)
            pos += hopSize
        }

        if (powers.isEmpty()) return -24.0

        // Absolute gate: -70 LUFS
        val absGate = 10.0.pow(-7.0) // -70 LUFS = 10^(-70/10) = 10^-7
        val gatedPowers1 = powers.filter { it > absGate }
        if (gatedPowers1.isEmpty()) return -70.0

        val meanGated1 = gatedPowers1.sum() / gatedPowers1.size

        // Relative gate: -10 LU below gated average
        val relGate = meanGated1 / 10.0
        val gatedPowers2 = powers.filter { it > relGate }
        if (gatedPowers2.isEmpty()) return -70.0

        val meanGated2 = gatedPowers2.sum() / gatedPowers2.size

        // Integrated loudness
        return -0.691 + 10.0 * log10(meanGated2)
    }

    private fun applyBiquad(
        input: FloatArray,
        b0: Double, b1: Double, b2: Double,
        a1: Double, a2: Double
    ): FloatArray {
        val output = FloatArray(input.size)
        var x1 = 0.0; var x2 = 0.0
        var y1 = 0.0; var y2 = 0.0
        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[i] = y0.toFloat().coerceIn(-1f, 1f)
            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
        return output
    }

    // Simple linear resampling
    private fun resample(samples: FloatArray, inRate: Int, outRate: Int, channels: Int): FloatArray {
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLen = (samples.size / ratio).toInt()
        val result = FloatArray(outLen)
        for (i in result.indices) {
            val srcIdx = (i * ratio).toInt() * channels
            if (srcIdx + channels <= samples.size) {
                val frac = (i * ratio) - (i * ratio).toInt()
                for (c in 0 until channels) {
                    val a = samples[srcIdx + c]
                    val b = if (srcIdx + channels + c < samples.size) samples[srcIdx + channels + c] else a
                    result[i * channels + c] = (a * (1 - frac) + b * frac).toFloat()
                }
            }
        }
        return result
    }

    private fun encodeAac(samples: FloatArray, sampleRate: Int, channels: Int, outputFile: File): Boolean {
        val mime = "audio/mp4a-latm"
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }

        val encoder = MediaCodec.createEncoderByType(mime)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val fos = FileOutputStream(outputFile)

        try {
            // Convert float to short PCM
            val pcmShorts = ShortArray(samples.size) { (samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }

            var inputOffset = 0
            val frameSize = 1024 * channels // AAC frame size
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        val remaining = pcmShorts.size - inputOffset
                        if (remaining <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val toWrite = minOf(remaining, buf.capacity() / 2)
                            buf.asShortBuffer().put(pcmShorts, inputOffset, toWrite)
                            encoder.queueInputBuffer(inIdx, 0, toWrite * 2,
                                inputOffset.toLong() * 1_000_000 / (sampleRate * channels), 0)
                            inputOffset += toWrite
                        }
                    }
                }

                // Collect output
                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outIdx < 0 -> continue
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }

                if (bufferInfo.size > 0) {
                    val outBuf = encoder.getOutputBuffer(outIdx)!!
                    val bytes = ByteArray(bufferInfo.size)
                    outBuf.position(bufferInfo.offset)
                    outBuf.get(bytes, 0, bufferInfo.size)

                    // Write ADTS header
                    val adtsHeader = createAdtsHeader(bufferInfo.size, sampleRate, channels)
                    fos.write(adtsHeader)
                    fos.write(bytes)
                }
                encoder.releaseOutputBuffer(outIdx, false)
            }

            fos.flush()
            return true
        } finally {
            fos.close()
            encoder.stop()
            encoder.release()
        }
    }

    private fun createAdtsHeader(dataLen: Int, sampleRate: Int, channels: Int): ByteArray {
        val totalLen = dataLen + 7
        val profile = 1 // AAC LC
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            7350 -> 12; else -> 4 // default 44100
        }
        val chanCfg = channels

        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = (0xF1).toByte() // MPEG-4, no CRC
        header[2] = ((profile - 1 shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        header[3] = ((chanCfg and 3 shl 6) + (totalLen shr 11)).toByte()
        header[4] = (totalLen shr 3 and 0xFF).toByte()
        header[5] = ((totalLen and 7 shl 5) + 0x1F).toByte()
        header[6] = 0xFC.toByte()
        return header
    }
}
