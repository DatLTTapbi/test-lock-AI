package com.example.testlock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Handler
import android.util.Log
import com.example.testlock.service.LockScreenService
import com.example.testlock.ui.theme.TestlockTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.sqrt

object VoiceLockState {
    var referenceFrames: List<FloatArray>? = null
    var targetKeyword: String? = null
}

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handle audio permission result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            TestlockTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LockScreenTestScreen(
                        modifier = Modifier.padding(innerPadding),
                        onCheckPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                startActivity(intent)
                            }
                        },
                        onStartService = {
                            val intent = Intent(this, LockScreenService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        },
                        onStopService = {
                            val intent = Intent(this, LockScreenService::class.java)
                            stopService(intent)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun LockScreenTestScreen(
    modifier: Modifier = Modifier,
    onCheckPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    var showVoiceSetup by remember { mutableStateOf(false) }
    var showVoiceVerify by remember { mutableStateOf(false) }
    var showKeywordSetup by remember { mutableStateOf(false) }
    var showKeywordVerify by remember { mutableStateOf(false) }

    when {
        showVoiceSetup -> {
            VoiceLockSetupScreen(onDismiss = { showVoiceSetup = false })
        }
        showVoiceVerify -> {
            VoiceVerificationScreen(onDismiss = { showVoiceVerify = false })
        }
        showKeywordSetup -> {
            SpeechKeywordSetupScreen(onDismiss = { showKeywordSetup = false })
        }
        showKeywordVerify -> {
            SpeechKeywordVerifyScreen(onDismiss = { showKeywordVerify = false })
        }
        else -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "AI Voice Lock Dashboard",
                    fontSize = 24.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                Button(
                    onClick = { onCheckPermission() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text("1. Cấp Quyền Overlay")
                }

                Button(
                    onClick = { onStartService() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("2. Bắt đầu Foreground Service")
                }

                Button(
                    onClick = { onStopService() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("3. Dừng Service")
                }

                Button(
                    onClick = { showVoiceSetup = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("4. Thiết lập Khóa Giọng Nói (Vector DTW)")
                }

                Button(
                    onClick = { showVoiceVerify = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text("5. Xác thực Giọng Nói (Vector DTW)")
                }

                Button(
                    onClick = { showKeywordSetup = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text("6. Đặt Mật Khẩu Bằng Giọng Nói (Keyword Setup)")
                }

                Button(
                    onClick = { showKeywordVerify = true },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Text("7. Xác Thực Từ Khóa (SpeechRecognizer)")
                }
            }
        }
    }
}

@Composable
fun SpeechKeywordSetupScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var setupState by remember { mutableStateOf("Idle") }
    var instructionText by remember { mutableStateOf("Nhấn nút và nói từ khóa mật khẩu của bạn (ví dụ: 'mở khóa', 'hello')") }
    var registeredKeyword by remember { mutableStateOf(VoiceLockState.targetKeyword ?: "") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Đặt Mật Khẩu Bằng Giọng Nói",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = instructionText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (registeredKeyword.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = "Mật khẩu hiện tại: \"$registeredKeyword\"",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                if (setupState == "Idle") {
                    setupState = "Listening"
                    instructionText = "Đang lắng nghe khẩu lệnh..."

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val speechResult = recognizeSpeech(context)
                            withContext(Dispatchers.Main) {
                                setupState = "Idle"
                                if (speechResult.startsWith("Lỗi") || speechResult.contains("Không nhận diện")) {
                                    instructionText = "Không nhận diện được: $speechResult. Thử lại!"
                                } else {
                                    VoiceLockState.targetKeyword = speechResult
                                    registeredKeyword = speechResult
                                    instructionText = "Thành công! Đã ghi nhớ mật khẩu."
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                setupState = "Idle"
                                instructionText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = setupState == "Idle",
            modifier = Modifier.size(140.dp, 56.dp)
        ) {
            Text(text = if (setupState == "Listening") "Đang nghe..." else "Nói Mật Khẩu")
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Xong / Quay lại")
        }
    }
}

@Composable
fun SpeechKeywordVerifyScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var verifyState by remember { mutableStateOf("Idle") }
    var instructionText by remember { mutableStateOf("Nhấn nút và nói từ khóa để xác thực") }
    var recognizedText by remember { mutableStateOf("") }
    var matchResultText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    val targetKw = VoiceLockState.targetKeyword

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Xác Thực Từ Khóa (SpeechRecognizer)",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (targetKw.isNullOrEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Chưa đặt mật khẩu từ khóa! Vui lòng hoàn thành bước 6 trước.",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        } else {
            Text(
                text = "Mật khẩu đã đặt: \"$targetKw\"",
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 12.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = instructionText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (recognizedText.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Bạn đã nói: \"$recognizedText\"",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text(
                        text = matchResultText,
                        fontSize = 16.sp,
                        color = if (matchResultText.contains("THÀNH CÔNG")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Button(
            onClick = {
                if (targetKw.isNullOrEmpty()) {
                    instructionText = "Vui lòng đặt mật khẩu ở bước 6 trước!"
                    return@Button
                }

                if (verifyState == "Idle") {
                    verifyState = "Listening"
                    instructionText = "Đang lắng nghe xác thực..."
                    recognizedText = ""
                    matchResultText = ""

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val speechResult = recognizeSpeech(context)
                            val cleanedRecognized = speechResult.trim().lowercase()
                            val cleanedTarget = targetKw.trim().lowercase()
                            val isMatch = cleanedRecognized.contains(cleanedTarget)

                            withContext(Dispatchers.Main) {
                                verifyState = "Idle"
                                recognizedText = speechResult
                                matchResultText = if (isMatch) {
                                    "✅ XÁC THỰC THÀNH CÔNG!\nKhớp với từ khóa \"$targetKw\""
                                } else {
                                    "❌ XÁC THỰC THẤT BẠI!\nKhông khớp (nhận diện được \"$speechResult\")"
                                }
                                instructionText = "Hoàn tất xác thực!"
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                verifyState = "Idle"
                                instructionText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = verifyState == "Idle" && !targetKw.isNullOrEmpty(),
            modifier = Modifier.size(140.dp, 56.dp)
        ) {
            Text(text = if (verifyState == "Listening") "Đang nghe..." else "Xác Thực Ngay")
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = { onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Quay lại")
        }
    }
}

@Composable
fun VoiceLockSetupScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var recordingState by remember { mutableStateOf("Idle") }
    var progressText by remember { mutableStateOf("Nhấn nút Record để ghi âm khẩu lệnh đăng ký (tối đa 4s)") }
    var recognizedText by remember { mutableStateOf("") }
    var isSuccess by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Voice Lock Setup (Step 1)",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = progressText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (recognizedText.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = "Khẩu lệnh: \"$recognizedText\"",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                if (recordingState == "Idle") {
                    recordingState = "Recording"
                    recognizedText = ""
                    progressText = "Đang ghi âm (AudioRecord 16kHz)..."
                    
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val sampleRate = 16000
                            val channelConfig = AudioFormat.CHANNEL_IN_MONO
                            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                            
                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                val recorder = AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    maxOf(minBufferSize, 3200)
                                )

                                val audioData = mutableListOf<Float>()
                                val buffer = ByteArray(512 * 2)
                                recorder.startRecording()
                                
                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 4000) {
                                    val readSize = recorder.read(buffer, 0, buffer.size)
                                    if (readSize > 0) {
                                        for (i in 0 until readSize step 2) {
                                            val s = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                            audioData.add(s / 32768.0f)
                                        }
                                    }
                                }
                                recorder.stop()
                                recorder.release()

                                withContext(Dispatchers.Main) {
                                    recordingState = "Processing"
                                    progressText = "Đang chạy Silero VAD kiểm tra tiếng người..."
                                }

                                val ortEnvironment = OrtEnvironment.getEnvironment()
                                val vadBytes = context.assets.open("model/silero_vad_16k_op15.onnx").readBytes()
                                val vadSession = ortEnvironment.createSession(vadBytes)
                                val hasHumanSpeech = checkSpeechWithSileroVAD(vadSession, ortEnvironment, audioData)
                                vadSession.close()

                                if (!hasHumanSpeech) {
                                    withContext(Dispatchers.Main) {
                                        recordingState = "Idle"
                                        progressText = "Không phát hiện thấy giọng nói! Vui lòng thử lại."
                                    }
                                    return@launch
                                }

                                withContext(Dispatchers.Main) {
                                    progressText = "Phát hiện giọng nói! Đang nhận diện từ..."
                                }

                                val speechResult = recognizeSpeech(context)

                                withContext(Dispatchers.Main) {
                                    recognizedText = speechResult
                                    progressText = "Đang trích xuất Speech Embedding (96 chiều)..."
                                }

                                val embBytes = context.assets.open("model/speech-embedding.onnx").readBytes()
                                val embSession = ortEnvironment.createSession(embBytes)
                                val embeddingFrames = extractEmbeddingFrames(embSession, ortEnvironment, audioData)
                                embSession.close()

                                VoiceLockState.referenceFrames = embeddingFrames
Log.d("faewfawe","${VoiceLockState.referenceFrames}")
                                withContext(Dispatchers.Main) {
                                    recordingState = "Success"
                                    isSuccess = true
                                    progressText = "Thành công! Đã lưu mẫu chuẩn (Reference Vector)."
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    recordingState = "Idle"
                                    progressText = "Chưa cấp quyền RECORD_AUDIO!"
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                recordingState = "Idle"
                                progressText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = recordingState == "Idle",
            modifier = Modifier.size(120.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recordingState == "Recording") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(text = if (recordingState == "Recording") "Recording" else "Record", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = { onDismiss() },
            enabled = isSuccess,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Back")
        }
    }
}

@Composable
fun VoiceVerificationScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var verifyState by remember { mutableStateOf("Idle") }
    var verifyText by remember { mutableStateOf("Nhấn nút Verify để ghi âm xác thực giọng nói") }
    var matchResult by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Voice Lock Verification (DTW)",
            fontSize = 22.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = verifyText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (matchResult.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = matchResult,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Button(
            onClick = {
                if (VoiceLockState.referenceFrames == null) {
                    verifyText = "Chưa có mẫu giọng nói đăng ký! Hãy chạy bước 4 (Setup) trước."
                    return@Button
                }

                if (verifyState == "Idle") {
                    verifyState = "Recording"
                    verifyText = "Đang ghi âm xác thực (16kHz)..."
                    matchResult = ""

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val sampleRate = 16000
                            val channelConfig = AudioFormat.CHANNEL_IN_MONO
                            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                val recorder = AudioRecord(
                                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                                    sampleRate,
                                    channelConfig,
                                    audioFormat,
                                    maxOf(minBufferSize, 3200)
                                )

                                val audioData = mutableListOf<Float>()
                                val buffer = ByteArray(512 * 2)
                                recorder.startRecording()

                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 4000) {
                                    val readSize = recorder.read(buffer, 0, buffer.size)
                                    if (readSize > 0) {
                                        for (i in 0 until readSize step 2) {
                                            val s = ((buffer[i+1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort()
                                            audioData.add(s / 32768.0f)
                                        }
                                    }
                                }
                                recorder.stop()
                                recorder.release()

                                withContext(Dispatchers.Main) {
                                    verifyState = "Processing"
                                    verifyText = "Đang trích xuất đặc trưng & so khớp DTW..."
                                }

                                val ortEnvironment = OrtEnvironment.getEnvironment()
                                val embBytes = context.assets.open("model/speech-embedding.onnx").readBytes()
                                val embSession = ortEnvironment.createSession(embBytes)

                                val queryFrames = extractEmbeddingFrames(embSession, ortEnvironment, audioData)
                                embSession.close()

                                val refFrames = VoiceLockState.referenceFrames!!
                                val dtwDistance = computeDTWDistance(queryFrames, refFrames)

                                val isMatch = dtwDistance < 0.45f

                                withContext(Dispatchers.Main) {
                                    verifyState = "Idle"
                                    verifyText = "Hoàn tất xác thực!"
                                    matchResult = if (isMatch) {
                                        "✅ XÁC THỰC THÀNH CÔNG!\nKhoảng cách DTW: %.4f (Khớp giọng)".format(dtwDistance)
                                    } else {
                                        "❌ XÁC THỰC THẤT BẠI!\nKhoảng cách DTW: %.4f (Không khớp)".format(dtwDistance)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                verifyState = "Idle"
                                verifyText = "Lỗi: ${e.localizedMessage}"
                            }
                        }
                    }
                }
            },
            enabled = verifyState == "Idle",
            modifier = Modifier.size(120.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (verifyState == "Recording") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            )
        ) {
            Text(text = if (verifyState == "Recording") "Verify" else "Verify", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedButton(
            onClick = { onDismiss() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
        ) {
            Text("Back")
        }
    }
}

// 1, 2, 3. DTW and Cosine Distance implementation requested by user
fun cosineDistance(bVar: FloatArray, bVar2: FloatArray): Float {
    var f12 = 0.0f
    var norm1 = 0.0f
    var norm2 = 0.0f
    for (i12 in 0 until 96) {
        f12 += bVar[i12] * bVar2[i12]
        norm1 += bVar[i12] * bVar[i12]
        norm2 += bVar2[i12] * bVar2[i12]
    }
    val f10 = sqrt(norm1)
    val f11 = sqrt(norm2)
    if (f10 == 0.0f || f11 == 0.0f) return 1.0f
    val cosineSimilarity = f12 / (f10 * f11)
    return 1.0f - cosineSimilarity
}

fun computeDTWDistance(queryFrames: List<FloatArray>, refFrames: List<FloatArray>): Float {
    val n = queryFrames.size
    val m = refFrames.size
    if (n == 0 || m == 0) return Float.MAX_VALUE

    val dtw = Array(n + 1) { FloatArray(m + 1) { Float.MAX_VALUE } }
    dtw[0][0] = 0.0f

    for (i in 1..n) {
        for (j in 1..m) {
            val cost = cosineDistance(queryFrames[i - 1], refFrames[j - 1])
            dtw[i][j] = cost + minOf(
                dtw[i - 1][j],
                dtw[i][j - 1],
                dtw[i - 1][j - 1]
            )
        }
    }
    return dtw[n][m] / (n + m)
}

suspend fun recognizeSpeech(context: Context): String = suspendCancellableCoroutine { continuation ->
    Handler(context.mainLooper).post {
        try {
            val speechRecognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    speechRecognizer.destroy()
                    if (continuation.isActive) {
                        continuation.resume("Lỗi nhận diện (code: $error)")
                    }
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    speechRecognizer.destroy()
                    if (continuation.isActive) {
                        if (!matches.isNullOrEmpty()) {
                            continuation.resume(matches[0])
                        } else {
                            continuation.resume("Không nhận diện được từ")
                        }
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            speechRecognizer.startListening(intent)
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume("Lỗi SpeechRecognizer: ${e.localizedMessage}")
            }
        }
    }
}

fun checkSpeechWithSileroVAD(
    vadSession: OrtSession,
    env: OrtEnvironment,
    audioFloatList: List<Float>,
    chunkSize: Int = 512
): Boolean {
    Log.d("SileroVAD", "Starting VAD check: audioFloatList size = ${audioFloatList.size}, chunkSize = $chunkSize")
    if (audioFloatList.size < chunkSize) {
        Log.d("SileroVAD", "Audio size ${audioFloatList.size} is less than chunkSize $chunkSize -> returning false")
        return false
    }
    val paddedList = audioFloatList.toMutableList()
    while (paddedList.size % chunkSize != 0) {
        paddedList.add(0.0f)
    }

    val srTensor = OnnxTensor.createTensor(env, longArrayOf(16000))
    var speechChunkCount = 0
    val totalChunks = paddedList.size / chunkSize
    Log.d("SileroVAD", "Total chunks to process: $totalChunks (padded size: ${paddedList.size})")

    for (i in 0 until totalChunks) {
        val chunk = FloatArray(chunkSize)
        for (j in 0 until chunkSize) {
            chunk[j] = paddedList[i * chunkSize + j]
        }
        val inputTensor = OnnxTensor.createTensor(env, arrayOf(chunk))
        val stateArray = Array(2) { Array(1) { FloatArray(128) } }
        val stateTensor = OnnxTensor.createTensor(env, stateArray)

        val inputs = mapOf(
            "input" to inputTensor,
            "sr" to srTensor,
            "state" to stateTensor
        )

        try {
            val results = vadSession.run(inputs)
            val outputTensor = results[0] as OnnxTensor
            val outputArray = outputTensor.floatBuffer.let { buffer ->
                val arr = FloatArray(buffer.remaining())
                buffer.get(arr)
                arr
            }
            val prob = if (outputArray.isNotEmpty()) outputArray[0] else -1f
            if (prob > 0.3f) {
                speechChunkCount++
                Log.d("SileroVAD", "Chunk $i/$totalChunks: SPEECH detected (prob = $prob)")
            } else {
                // Use Log.v or Log.d if you want to see non-speech chunks. Let's use Log.d or verbose.
                // Log.d("SileroVAD", "Chunk $i/$totalChunks: NO speech (prob = $prob)")
            }
            results.close()
        } catch (ex: Exception) {
            Log.e("SileroVAD", "Error running VAD chunk $i", ex)
        } finally {
            inputTensor.close()
            stateTensor.close()
        }
    }

    srTensor.close()

    val threshold = maxOf(2, totalChunks / 20)
    val hasSpeech = speechChunkCount >= threshold
    Log.d("SileroVAD", "VAD finished: speechChunkCount = $speechChunkCount, threshold = $threshold, hasSpeech = $hasSpeech")
    return hasSpeech
}

fun extractEmbeddingFrames(
    embSession: OrtSession,
    env: OrtEnvironment,
    audioFloatList: List<Float>
): List<FloatArray> {
    val inputArray = arrayOf(audioFloatList.toFloatArray())
    val inputTensor = OnnxTensor.createTensor(env, inputArray)
    
    val inputName = embSession.inputNames.iterator().next()
    val inputs = mapOf(inputName to inputTensor)

    try {
        val results = embSession.run(inputs)
        val outputTensor = results[0] as OnnxTensor
        val outputBuffer = outputTensor.floatBuffer
        val totalElements = outputBuffer.remaining()
        val data = FloatArray(totalElements)
        outputBuffer.get(data)
        
        results.close()
        inputTensor.close()

        val frames = mutableListOf<FloatArray>()
        val dim = 96
        val numFrames = totalElements / dim

        for (f in 0 until maxOf(1, numFrames)) {
            val frame = FloatArray(dim)
            val start = f * dim
            val end = minOf(start + dim, totalElements)
            for (k in 0 until (end - start)) {
                frame[k] = data[start + k]
            }
            frames.add(frame)
        }
        return frames
    } catch (e: Exception) {
        inputTensor.close()
        throw e
    }
}
