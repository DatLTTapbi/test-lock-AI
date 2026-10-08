package com.example.testlock.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import java.util.concurrent.atomic.AtomicBoolean

class AudioRecorder(
    private val context: Context
) {

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        audioFormat
    )

    private val bufferSize = maxOf(minBufferSize, 3200)
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onAudio: (FloatArray, Int) -> Unit) {
        if (isRecording.get()) return

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord initialization failed")
        }

        audioRecord = record
        isRecording.set(true)
        record.startRecording()

        // Phân bổ sẵn buffer để tránh tạo object liên tục (GC pressure)
        val pcmBuffer = ByteArray(1024)
        val floatBuffer = FloatArray(pcmBuffer.size / 2)

        Thread({
            while (isRecording.get()) {
                val readSize = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (readSize > 0) {
                    val sampleCount = readSize / 2
                    
                    for (i in 0 until sampleCount) {
                        val low = pcmBuffer[i * 2].toInt() and 0xFF
                        val high = pcmBuffer[i * 2 + 1].toInt()
                        
                        val sample = ((high shl 8) or low).toShort()
                        floatBuffer[i] = sample / 32768f
                    }

                    onAudio(floatBuffer, sampleCount)
                }
            }
        }, "AudioRecorderThread").start()
    }

    fun stop() {
        if (!isRecording.get()) return
        isRecording.set(false)
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioRecord = null
        }
    }
}
