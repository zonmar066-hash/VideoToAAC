package com.glenn.videotoaac

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.*
import java.io.*
import kotlin.math.log10
import kotlin.math.pow

/**
 * Streaming audio pipeline — zero OOM risk regardless of video length.
 *
 * Architecture (ref: Google UAMP, ExoPlayer decoder pattern):
 *   Pass 1: Extract audio → temp PCM file + measure LUFS (streaming)
 *   Pass 2: (if resample needed) Streaming resample → second temp PCM
 *   Pass 3: Read PCM, apply gain per frame, encode AAC with ADTS headers
 *
 * Memory bounded to ~192KB (stream buffer) + block-power accumulator (~56KB for 1hr).
 */
object AudioPipeline {

    data class Meta(
        val sampleRate: Int,
        val channels: Int,
        val measuredLufs: Double,
        val pcmFrames: Long  // frames = fileSize / (2 * channels)
    )

    private const val AAC_BITRATE = 128_000

    // ═══ Pass 1: Extract + measure ════════════════════════════════════

    suspend fun extractAndMeasure(
        context: Context,
        inputUri: Uri,
        pcmFile: File,
        onProgress: suspend (String) -> Unit
    ): Meta = withContext(Dispatchers.IO) {
        val videoFile = File(context.cacheDir, "v2a_src_${System.nanoTime()}.mp4")
        try {
            onProgress("复制视频…")
            context.contentResolver.openInputStream(inputUri)?.use { src ->
                videoFile.outputStream().buffered().use { dst -> src.copyTo(dst) }
            } ?: throw RuntimeException("无法读取视频文件")

            onProgress("提取音轨…")
            extractToPcm(videoFile, pcmFile)
        } finally {
            videoFile.delete()
        }
    }

    private fun extractToPcm(videoFile: File, pcmFile: File): Meta {
        val extractor = MediaExtractor()
        extractor.setDataSource(videoFile.absolutePath)

        // Locate first audio track
        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                trackIdx = i; format = f; break
            }
        }
        require(trackIdx >= 0 && format != null) { "未找到音频轨道" }

        extractor.selectTrack(trackIdx)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val meter = LufsMeter(sampleRate)
        val fos = DataOutputStream(BufferedOutputStream(FileOutputStream(pcmFile), 256 * 1024))
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var frameCount = 0L

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = decoder.dequeueInputBuffer(5000)
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

            val outIdx = decoder.dequeueOutputBuffer(bufInfo, 5000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outIdx < 0 -> continue
            }

            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

            if (bufInfo.size > 0) {
                val outBuf = decoder.getOutputBuffer(outIdx)!!
                val bytes = ByteArray(bufInfo.size)
                outBuf.position(bufInfo.offset)
                outBuf.get(bytes, 0, bufInfo.size)
                fos.write(bytes)

                // Feed LUFS meter: downmix each frame to mono, normalize to ±1
                val nShorts = bufInfo.size / 2
                var i = 0
                while (i + channels <= nShorts) {
                    var monoAcc = 0.0
                    for (c in 0 until channels) {
                        val idx = (i + c) * 2
                        val s = ((bytes[idx + 1].toInt() shl 8) or (bytes[idx].toInt() and 0xFF)).toShort()
                        monoAcc += s.toDouble()
                    }
                    meter.feed(monoAcc / (channels * 32768.0))
                    i += channels
                }
                frameCount += nShorts / channels
            }
            decoder.releaseOutputBuffer(outIdx, false)
        }

        decoder.stop(); decoder.release(); extractor.release(); fos.close()

        val lufs = meter.finish()
        return Meta(sampleRate, channels, lufs, frameCount)
    }

    // ═══ Pass 2: Streaming resample ═══════════════════════════════════

    suspend fun resamplePcm(
        inputFile: File,
        outputFile: File,
        inRate: Int,
        outRate: Int,
        channels: Int
    ) = withContext(Dispatchers.IO) {
        if (inRate == outRate) { inputFile.renameTo(outputFile); return@withContext }

        val ratio = outRate.toDouble() / inRate
        val inFrames = inputFile.length() / (2 * channels)
        val outFrames = (inFrames * ratio).toLong()

        val din = DataInputStream(BufferedInputStream(FileInputStream(inputFile)))
        val dos = DataOutputStream(BufferedOutputStream(FileOutputStream(outputFile)))
        val buf = ShortArray(channels * 2) // two consecutive frames for interpolation
        val bufFloats = FloatArray(buf.size)

        // Read first two frames
        var bufFill = 0
        while (bufFill < buf.size) {
            try { buf[bufFill] = din.readShort(); bufFill++ }
            catch (_: EOFException) { break }
        }

        for (outFrame in 0 until outFrames) {
            val srcPos = outFrame / ratio
            val srcFrame = srcPos.toInt()
            val frac = srcPos - srcFrame

            // Ensure buffer has enough data ahead
            val needAhead = (srcFrame + 2) * channels
            while (bufFill < needAhead + channels) {
                try { buf[bufFill % buf.size] = din.readShort(); bufFill++ }
                catch (_: EOFException) { break }
            }

            if (srcFrame * channels + channels > bufFill) break

            for (c in 0 until channels) {
                val a = buf[((srcFrame * channels + c) % buf.size)]
                val b = buf[((srcFrame * channels + channels + c) % buf.size)]
                val v = ((a * (1.0 - frac) + b * frac) * 32767.0).toInt().coerceIn(-32768, 32767)
                dos.writeShort(v)
            }

            // Slide buffer window forward occasionally
            if (outFrame % 256 == 0L && bufFill > buf.size * 4) {
                val keepFrom = srcFrame * channels
                val shift = keepFrom - (bufFill % buf.size)
                bufFill -= keepFrom
            }
        }

        din.close(); dos.close()
    }

    // ═══ Pass 3: Normalize + encode ═══════════════════════════════════

    suspend fun normalizeAndEncode(
        pcmFile: File,
        meta: Meta,
        targetSampleRate: Int,
        aacFile: File,
        onProgress: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val outRate = targetSampleRate
        val gainLinear = 10.0.pow((-14.0 - meta.measuredLufs) / 20.0).toFloat()
        val gainDb = 20.0 * log10(gainLinear.toDouble())

        onProgress("归一化: ${"%.1f".format(meta.measuredLufs)} → -14 LUFS (${"%.1f".format(gainDb)} dB)")

        val din = DataInputStream(BufferedInputStream(FileInputStream(pcmFile), 256 * 1024))
        val encoder = createEncoder(outRate, meta.channels)
        val fos = BufferedOutputStream(FileOutputStream(aacFile), 256 * 1024)
        val bufInfo = MediaCodec.BufferInfo()
        var inputDone = false; var outputDone = false
        var pts = 0L
        val ch = meta.channels
        val frameSize = 1024 * ch

        val rawFloats = FloatArray(frameSize)
        val rawShorts = ShortArray(frameSize)

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = encoder.dequeueInputBuffer(5000)
                if (inIdx >= 0) {
                    var read = 0
                    var eof = false
                    for (i in 0 until frameSize) {
                        try { rawFloats[i] = din.readShort().toFloat() / 32768f; read++ }
                        catch (_: EOFException) { eof = true; break }
                    }

                    if (eof && read == 0) {
                        encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (i in 0 until read) {
                            rawShorts[i] = (rawFloats[i] * gainLinear * 32767f)
                                .toInt().coerceIn(-32768, 32767).toShort()
                        }
                        val encIn = encoder.getInputBuffer(inIdx)!!
                        encIn.clear()
                        encIn.asShortBuffer().put(rawShorts, 0, read)
                        encoder.queueInputBuffer(inIdx, 0, read * 2, pts, 0)
                        pts += read.toLong() * 1_000_000L / (outRate * ch)
                    }
                }
            }

            val outIdx = encoder.dequeueOutputBuffer(bufInfo, 5000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outIdx < 0 -> continue
            }

            if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true

            if (bufInfo.size > 0) {
                val outBuf = encoder.getOutputBuffer(outIdx)!!
                val bytes = ByteArray(bufInfo.size)
                outBuf.position(bufInfo.offset)
                outBuf.get(bytes, 0, bufInfo.size)
                fos.write(createAdtsHeader(bufInfo.size, outRate, ch))
                fos.write(bytes)
            }
            encoder.releaseOutputBuffer(outIdx, false)
        }

        din.close(); fos.close(); encoder.stop(); encoder.release()
    }

    // ═══ Helpers ══════════════════════════════════════════════════════

    private fun createEncoder(sampleRate: Int, channels: Int): MediaCodec {
        val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType("audio/mp4a-latm")
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        return enc
    }

    private fun createAdtsHeader(dataLen: Int, sampleRate: Int, channels: Int): ByteArray {
        val totalLen = dataLen + 7
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            7350 -> 12; else -> 4
        }
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            ((0 shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte(),
            ((channels and 3 shl 6) + (totalLen shr 11)).toByte(),
            (totalLen shr 3 and 0xFF).toByte(),
            ((totalLen and 7 shl 5) + 0x1F).toByte(),
            0xFC.toByte()
        )
    }

    // ═══ LUFS Meter (streaming EBU R128) ══════════════════════════════

    private class LufsMeter(sampleRate: Int) {
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        private var hx1 = 0.0; private var hx2 = 0.0; private var hy1 = 0.0; private var hy2 = 0.0

        private var blockAcc = 0.0
        private var blockCnt = 0
        private val blockSize = (sampleRate * 0.4).toInt()
        private val powers = ArrayList<Double>(7200)

        fun feed(mono: Double) {
            // Stage 1: High-pass (ITU-R BS.1770-4 Table 1)
            val h1 = mono - 2.0 * x1 + x2 + 1.99004745483398 * y1 - 0.99007225036621 * y2
            x2 = x1; x1 = mono; y2 = y1; y1 = h1

            // Stage 2: High-shelf (ITU-R BS.1770-4 Table 2)
            val h2 = 1.53512485958697 * h1 - 2.69169618940638 * hx1 + 1.19839281085285 * hx2
            + 1.69065929318241 * hy1 - 0.73248077421585 * hy2
            hx2 = hx1; hx1 = h1; hy2 = hy1; hy1 = h2

            blockAcc += h2 * h2
            blockCnt++
            if (blockCnt >= blockSize) {
                powers.add(blockAcc / blockSize); blockAcc = 0.0; blockCnt = 0
            }
        }

        fun finish(): Double {
            if (blockCnt > 0) powers.add(blockAcc / blockCnt)
            if (powers.isEmpty()) return -70.0
            val g1 = powers.filter { it > 1e-7 }
            if (g1.isEmpty()) return -70.0
            val m1 = g1.sum() / g1.size
            val g2 = powers.filter { it > m1 / 10.0 }
            if (g2.isEmpty()) return -70.0
            val m2 = g2.sum() / g2.size
            return -0.691 + 10.0 * log10(m2)
        }
    }
}
