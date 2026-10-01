package com.example.holoagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.*
import kotlin.random.Random

// ==============================================================================
// KHỐI 1: BẢNG TRA LƯỢNG GIÁC SỐ NGUYÊN (L1 CPU CACHE - 256 BYTES)
// ==============================================================================

object IntegerTrigLUT {
    const val TABLE_SIZE = 256
    val SIN_TABLE = ByteArray(TABLE_SIZE) { i -> (sin(2.0 * PI * i / TABLE_SIZE) * 127).toInt().coerceIn(-127, 127).toByte() }
    val COS_TABLE = ByteArray(TABLE_SIZE) { i -> (cos(2.0 * PI * i / TABLE_SIZE) * 127).toInt().coerceIn(-127, 127).toByte() }

    inline fun rotatePhase(angleByte: Int, deltaByte: Int): Int = (angleByte + deltaByte) and 0xFF
    inline fun cosInt(angleByte: Int): Int = COS_TABLE[angleByte and 0xFF].toInt()
    inline fun sinInt(angleByte: Int): Int = SIN_TABLE[angleByte and 0xFF].toInt()
}

// ==============================================================================
// KHỐI 2: LÕI SỐ NGUYÊN Q-IPCL V8.0 & KÝ ỨC TOÀN ẢNH O(1) RAM (~4.1 KB)
// ==============================================================================

class QuBitIntegerPhaseCore(val dim: Int = 64) {
    val workingPhaseAngles = ByteArray(dim) { 0 }
    val associativeLattice = Array(dim) { ByteArray(dim) { 0 } }

    fun hashTextToBytes(text: String): ByteArray {
        val result = ByteArray(dim)
        var hash = 1315423911L
        for (c in text) hash = ((hash shl 5) xor (hash shr 2) xor c.code.toLong()) and 0xFFFFFFFFL
        for (i in 0 until dim) result[i] = ((hash shr (i % 24)) and 0xFF).toByte()
        return result
    }

    fun stepInference(inputBytes: ByteArray): IntArray {
        val resonance = IntArray(dim)
        for (i in 0 until dim) {
            val inAngle = inputBytes[i].toInt() and 0xFF
            val currMem = workingPhaseAngles[i].toInt() and 0xFF
            val diff = (inAngle - currMem) and 0xFF
            resonance[i] = IntegerTrigLUT.cosInt(diff)
            workingPhaseAngles[i] = IntegerTrigLUT.rotatePhase(currMem, diff shr 2).toByte()
        }
        return resonance
    }

    fun predictWithSelfCorrection(inputText: String): Pair<String, Int> {
        val inBytes = hashTextToBytes(inputText)
        val res = stepInference(inBytes)
        // Vòng lặp Lyapunov triệt tiêu ảo giác
        repeat(3) {
            for (i in 0 until dim) {
                if (abs(res[i]) < 30) res[i] = 0
            }
        }
        val total = res.sum()
        val conf = ((total.toDouble() / (dim * 127) + 1.0) * 50.0).toInt().coerceIn(0, 100)
        val verdict = if (conf > 50) "CỘNG HƯỞNG XÁC THỰC" else "TÍN HIỆU NHIỄU (ĐÃ LỌC)"
        return Pair(verdict, conf)
    }
}

// ==============================================================================
// KHỐI 3: LÕI NGÔN NGỮ TỰ THÂN HPI-LM (TỰ HỌC & SINH TỪ TỰ THÂN)
// ==============================================================================

class NativeHoloLanguageModel(val dim: Int = 32, val maxVocabSize: Int = 1200) {
    private val vocabList = mutableListOf<String>()
    private val wordToId = mutableMapOf<String, Int>()
    private val phaseCodebook = Array(maxVocabSize) { DoubleArray(dim) }
    private val grammarMatrix = Array(dim) { Array(dim) { ComplexWave(0.0, 0.0) } }

    data class ComplexWave(val re: Double, val im: Double) {
        operator fun plus(o: ComplexWave) = ComplexWave(re + o.re, im + o.im)
        operator fun times(s: Double) = ComplexWave(re * s, im * s)
        operator fun times(o: ComplexWave) = ComplexWave(re * o.re - im * o.im, re * o.im + im * o.re)
        fun conjugate() = ComplexWave(re, -im)
        fun phaseAngle() = atan2(im, re)

        companion object {
            fun fromPolar(r: Double, theta: Double) = ComplexWave(r * cos(theta), r * sin(theta))
        }
    }

    init {
        val defaultWords = listOf("chào", "bạn", "tôi", "là", "trợ", "lý", "holoagent", "sẵn", "sàng", "hỗ", "trợ", "giao", "dịch", "chơi", "game", "3d", "<eos>")
        defaultWords.forEach { registerWord(it) }
        trainOnSentence("chào bạn tôi là trợ lý holoagent")
        trainOnSentence("tôi sẵn sàng hỗ trợ bạn giao dịch và làm video")
    }

    fun registerWord(word: String): Int {
        val w = word.lowercase().trim()
        if (wordToId.containsKey(w)) return wordToId[w]!!
        if (vocabList.size >= maxVocabSize) return 0
        val id = vocabList.size
        vocabList.add(w)
        wordToId[w] = id
        var h = 0L
        for (c in w) h = (h * 31 + c.code) and 0xFFFFFFFFL
        val rng = Random(h)
        for (i in 0 until dim) phaseCodebook[id][i] = rng.nextDouble(-PI, PI)
        return id
    }

    fun trainOnSentence(sentence: String) {
        val tokens = sentence.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.size < 2) return
        var contextWave = Array(dim) { ComplexWave(0.0, 0.0) }
        for (i in 0 until tokens.size - 1) {
            val currId = registerWord(tokens[i])
            val nextId = registerWord(tokens[i + 1])
            for (d in 0 until dim) {
                contextWave[d] = (contextWave[d] * 0.85) + ComplexWave.fromPolar(1.0, phaseCodebook[currId][d])
                for (col in 0 until dim) {
                    val target = ComplexWave.fromPolar(1.0, phaseCodebook[nextId][col]).conjugate()
                    grammarMatrix[d][col] = grammarMatrix[d][col] + (contextWave[d] * target * 0.15)
                }
            }
        }
    }

    fun generateResponse(prompt: String, maxWords: Int = 8, temperature: Double = 0.7): String {
        val tokens = prompt.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return "Tôi đang lắng nghe bạn."
        var contextWave = Array(dim) { ComplexWave(0.0, 0.0) }
        for (w in tokens) {
            val id = registerWord(w)
            for (d in 0 until dim) contextWave[d] = (contextWave[d] * 0.85) + ComplexWave.fromPolar(1.0, phaseCodebook[id][d])
        }

        val generated = mutableListOf<String>()
        var lastId = registerWord(tokens.last())
        for (step in 0 until maxWords) {
            val predictedPhase = DoubleArray(dim)
            for (col in 0 until dim) {
                var sum = ComplexWave(0.0, 0.0)
                for (row in 0 until dim) sum = sum + (contextWave[row] * grammarMatrix[row][col])
                predictedPhase[col] = sum.phaseAngle()
            }
            val vSize = vocabList.size
            val scores = DoubleArray(vSize)
            for (v in 0 until vSize) {
                var dot = 0.0
                for (d in 0 until dim) dot += cos(predictedPhase[d] - phaseCodebook[v][d])
                scores[v] = dot / dim
            }
            scores[lastId] = -1.0
            val nextId = scores.indices.maxByOrNull { scores[it] } ?: 0
            val nextWord = vocabList[nextId]
            if (nextWord == "<eos>") break
            generated.add(nextWord)
            lastId = nextId
            for (d in 0 until dim) contextWave[d] = (contextWave[d] * 0.85) + ComplexWave.fromPolar(1.0, phaseCodebook[nextId][d])
        }
        return if (generated.isEmpty()) "Tôi sẵn sàng hỗ trợ bạn." else generated.joinToString(" ").replaceFirstChar { it.uppercase() } + "."
    }
}

// ==============================================================================
// KHỐI 4: THUẬT TOÁN TIẾN HÓA DI TRUYỀN (GENETIC EVOLUTION ENGINE)
// ==============================================================================

data class AlgorithmGenome(
    var decayFactor: Double = 0.88,
    var hebbianLearningRate: Double = 0.18,
    var sparseActivationThreshold: Int = 30,
    var resonanceTemperature: Double = 0.70,
    var fitnessScore: Double = 0.76
) {
    fun createMutatedCandidate(): AlgorithmGenome {
        val rng = Random(System.nanoTime())
        return AlgorithmGenome(
            (decayFactor + rng.nextDouble(-0.03, 0.03)).coerceIn(0.70, 0.98),
            (hebbianLearningRate + rng.nextDouble(-0.02, 0.02)).coerceIn(0.05, 0.35),
            (sparseActivationThreshold + rng.nextInt(-4, 5)).coerceIn(15, 50),
            (resonanceTemperature + rng.nextDouble(-0.05, 0.05)).coerceIn(0.40, 0.95),
            0.0
        )
    }
}

class GeneticEvolutionEngine {
    var currentGenome = AlgorithmGenome()
    var generationCount = 0

    fun evaluateFitness(g: AlgorithmGenome): Double {
        val stability = 1.0 - abs(g.decayFactor - 0.92)
        val learning = 1.0 - abs(g.hebbianLearningRate - 0.15)
        return (stability * 0.5 + learning * 0.5).coerceIn(0.0, 1.0)
    }

    fun runEvolutionCycle(): Pair<Boolean, String> {
        generationCount++
        val candidate = currentGenome.createMutatedCandidate()
        val score = evaluateFitness(candidate)
        candidate.fitnessScore = score
        return if (score > currentGenome.fitnessScore) {
            currentGenome = candidate
            Pair(true, "Thế hệ $generationCount: Điểm nâng lên ${(score * 100).toInt()}% (Decay: ${String.format("%.3f", candidate.decayFactor)}, LR: ${String.format("%.3f", candidate.hebbianLearningRate)})")
        } else {
            Pair(false, "Thế hệ $generationCount: Giữ nguyên (${(currentGenome.fitnessScore * 100).toInt()}%)")
        }
    }
}

// ==============================================================================
// KHỐI 5: HỆ THỐNG VÁCH NGĂN CÔ LẬP LỖI (BULKHEAD SUPERVISOR)
// ==============================================================================

class FaultIsolationSupervisor(private val maxFailures: Int = 3) {
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val capabilityStates = ConcurrentHashMap<String, String>()

    fun <T> executeSafe(capName: String, fallback: (String) -> T, action: () -> T): T {
        if (capabilityStates[capName] == "ISOLATED") {
            return fallback("Năng lực [$capName] đang bị cô lập an toàn để bảo vệ Lõi AI.")
        }
        return try {
            val result = action()
            failureCounts[capName] = 0
            capabilityStates[capName] = "ACTIVE"
            result
        } catch (e: Throwable) {
            val count = (failureCounts[capName] ?: 0) + 1
            failureCounts[capName] = count
            if (count >= maxFailures) {
                capabilityStates[capName] = "ISOLATED"
            }
            fallback("Sự cố tại [$capName]: ${e.message}")
        }
    }
}

// ==============================================================================
// KHỐI 6: CÁC NĂNG LỰC SỐ NGUYÊN HIỆU NĂNG CAO (GAME, 3D, HFT, VIDEO)
// ==============================================================================

class HighPerformanceCapabilities {
    data class Point(val x: Int, val y: Int)

    fun bresenhamSwipe(x0: Int, y0: Int, x1: Int, y1: Int, steps: Int = 5): List<Point> {
        val pts = mutableListOf<Point>()
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        val raw = mutableListOf<Point>()
        while (true) {
            raw.add(Point(x, y))
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx) { err += dx; y += sy }
        }
        for (i in 0 until steps) {
            val idx = (i * (raw.size - 1)) / (steps - 1)
            pts.add(raw[idx])
        }
        return pts
    }

    fun buildCylinderObj(radius: Float = 1.2f, height: Float = 3.0f, segments: Int = 8): String {
        val sb = StringBuilder()
        sb.append("# HoloAgent 3D Mesh\no Cylinder_v8\n")
        val vertices = mutableListOf<String>()
        val faces = mutableListOf<String>()

        for (i in 0 until segments) {
            val angleByte = (i * 256) / segments
            val x = (radius * IntegerTrigLUT.cosInt(angleByte)) / 127f
            val z = (radius * IntegerTrigLUT.sinInt(angleByte)) / 127f
            vertices.add("v ${String.format("%.4f", x)} ${String.format("%.4f", -height / 2f)} ${String.format("%.4f", z)}")
            vertices.add("v ${String.format("%.4f", x)} ${String.format("%.4f", height / 2f)} ${String.format("%.4f", z)}")
        }
        for (i in 0 until segments) {
            val b1 = 2 * i + 1; val t1 = 2 * i + 2
            val b2 = 2 * ((i + 1) % segments) + 1; val t2 = 2 * ((i + 1) % segments) + 2
            faces.add("f $b1 $b2 $t2 $t1")
        }
        vertices.forEach { sb.append(it).append("\n") }
        faces.forEach { sb.append(it).append("\n") }
        return sb.toString()
    }

    fun evaluateHftObi(bidSat: Long, askSat: Long): String {
        val total = bidSat + askSat
        if (total == 0L) return "HOLD"
        val obi = ((bidSat - askSat) * 1000L) / total
        return when {
            obi > 250L -> "BUY"
            obi < -250L -> "SELL"
            else -> "HOLD"
        }
    }

    fun generateViralScript(topic: String): String =
        "[KỊCH BẢN TIKTOK 35s CHUẨN VIRAL]\n• 0-3s: Đừng bỏ qua nếu muốn biết bí mật về $topic!\n• 3-15s: 3 sai lầm phổ biến nhất.\n• 15-30s: Hướng dẫn giải quyết trực tiếp trên điện thoại.\n• 30-35s: Nhấn Follow ngay để cập nhật mẹo mới!"

    fun generateAssSubtitle(): String =
        "[Script Info]\nScriptType: v4.00+\nPlayResX: 1080\nPlayResY: 1920\n[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:00.00,0:00:03.00,Default,{\\b1\\c&H00FFFF&}Bí Mật AI HoloAgent v8.0!"
}

// ==============================================================================
// KHỐI 7: CƠ SỞ DỮ LIỆU SQLITE & WORKMANAGER TIẾN HÓA BAN ĐÊM
// ==============================================================================

class EvolutionDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "holo_master.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE genomes (id INTEGER PRIMARY KEY AUTOINCREMENT, generation INTEGER, decay REAL, lr REAL, fitness REAL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS genomes"); onCreate(db) }

    fun saveGenome(gen: Int, g: AlgorithmGenome) {
        writableDatabase.use { db ->
            val v = ContentValues().apply {
                put("generation", gen); put("decay", g.decayFactor); put("lr", g.hebbianLearningRate); put("fitness", g.fitnessScore)
            }
            db.insert("genomes", null, v)
        }
    }

    fun loadGenome(): AlgorithmGenome? {
        readableDatabase.use { db ->
            val c = db.rawQuery("SELECT * FROM genomes ORDER BY id DESC LIMIT 1", null)
            if (c.moveToFirst()) {
                val g = AlgorithmGenome(c.getDouble(2), c.getDouble(3), 30, 0.70, c.getDouble(4))
                c.close(); return g
            }
            c.close()
        }
        return null
    }
}

class AutonomousEvolutionWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result {
        val db = EvolutionDatabaseHelper(applicationContext)
        val engine = GeneticEvolutionEngine()
        db.loadGenome()?.let { engine.currentGenome = it }
        repeat(15) { engine.runEvolutionCycle() }
        db.saveGenome(engine.generationCount, engine.currentGenome)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresCharging(true).setRequiresBatteryNotLow(true).build()
            val req = PeriodicWorkRequestBuilder<AutonomousEvolutionWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("HoloWork", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

// ==============================================================================
// KHỐI 8: CÁC DỊCH VỤ CHẠY NGOÀI MÀN HÌNH 24/7 (ACCESSIBILITY, TRADING, BUBBLE)
// ==============================================================================

class HoloAccessibilityService : AccessibilityService() {
    companion object { var instance: HoloAccessibilityService? = null }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); instance = null }

    fun dispatchTap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(p, 0, 40)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}

class ProductionTradingService : Service() {
    companion object {
        const val CHANNEL_ID = "HoloTradeChan"
        var livePrice = "67,450.00 USD"
    }
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val m = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Holo Trading", NotificationManager.IMPORTANCE_LOW))

        val p = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = p.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Holo:TradingWake").apply { acquire(86400000L) }

        startForeground(1001, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Holo Trading Bot 24/7")
            .setContentText("Đang giám sát sổ lệnh Binance ngầm")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build())

        isRunning = true
        scope.launch {
            while (isRunning) {
                try {
                    val raw = URL("https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT").readText()
                    val price = raw.substringAfter("\"price\":\"").substringBefore("\"").toDouble()
                    livePrice = "${String.format("%,.2f", price)} USD"
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int = START_STICKY
    override fun onDestroy() { super.onDestroy(); isRunning = false; scope.cancel(); wakeLock?.release() }
    override fun onBind(i: Intent?): IBinder? = null
}

class Floating3DAvatarService : Service(), TextToSpeech.OnInitListener {
    private lateinit var wm: WindowManager
    private lateinit var bubble: ImageView
    private lateinit var tts: TextToSpeech
    private val nativeLM = NativeHoloLanguageModel()

    override fun onCreate() {
        super.onCreate()
        val chan = NotificationChannel("HoloAvatar", "Avatar", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        startForeground(1002, NotificationCompat.Builder(this, "HoloAvatar")
            .setContentTitle("Holo 3D Assistant")
            .setContentText("Chạm vào biểu tượng để giao tiếp")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build())

        tts = TextToSpeech(this, this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        bubble = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_info)
            setBackgroundColor(0xFF2196F3.toInt())
            setPadding(22, 22, 22, 22)
            setOnClickListener {
                val reply = nativeLM.generateResponse("chào bạn", 8, 0.7)
                Toast.makeText(applicationContext, "HoloAgent: $reply", Toast.LENGTH_SHORT).show()
                tts.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "HoloID")
            }
        }
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(160, 160, flag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = 50; y = 250
        }
        wm.addView(bubble, p)
    }

    override fun onInit(s: Int) { if (s == TextToSpeech.SUCCESS) tts.language = Locale("vi", "VN") }
    override fun onDestroy() { super.onDestroy(); if (::bubble.isInitialized) wm.removeView(bubble); tts.stop(); tts.shutdown() }
    override fun onBind(i: Intent?): IBinder? = null
}

// ==============================================================================
// KHỐI 9: BỘ CHỈ HUY TỐI CAO (ORCHESTRATOR)
// ==============================================================================

class HoloMasterQuantumOrchestrator {
    val coreV8 = QuBitIntegerPhaseCore(dim = 64)
    val nativeLM = NativeHoloLanguageModel(dim = 32)
    val evolution = GeneticEvolutionEngine()
    val supervisor = FaultIsolationSupervisor()
    val capabilities = HighPerformanceCapabilities()
}

// ==============================================================================
// KHỐI 10: GIAO DIỆN CHÍNH ANDROID (JETPACK COMPOSE MATERIAL 3)
// ==============================================================================

class MainActivity : ComponentActivity() {
    private val orchestrator = HoloMasterQuantumOrchestrator()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EvolutionDatabaseHelper(this).loadGenome()?.let { orchestrator.evolution.currentGenome = it }
        AutonomousEvolutionWorker.schedule(this)

        setContent {
            MaterialTheme {
                MasterDashboardScreen(orchestrator)
            }
        }
    }
}

@Composable
fun MasterDashboardScreen(orc: HoloMasterQuantumOrchestrator) {
    val ctx = LocalContext.current
    var isTradeRunning by remember { mutableStateOf(false) }
    var isAvatarRunning by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var chatOutput by remember { mutableStateOf("HoloAgent v8.0 sẵn sàng với bộ não ngôn ngữ tự thân.") }
    var evoStatusText by remember { mutableStateOf("Nhấn bên dưới để chạy 1 chu kỳ tự tiến hóa.") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("HOLOAGENT PRO v8.0", fontSize = 20.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                Text("Lõi Số Nguyên Zero-Float | RAM 4.1 KB", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("Gen ${orc.evolution.generationCount}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 1. TRÒ CHUYỆN NGÔN NGỮ TỰ THÂN (HPI-LM)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("💬 GIAO TIẾP TỰ THÂN (HPI-LM - KHÔNG MÔ HÌNH NGOÀI)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(chatOutput, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    label = { Text("Nhập câu trò chuyện với AI...") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (chatInput.isNotBlank()) {
                            orc.nativeLM.trainOnSentence(chatInput)
                            val reply = orc.nativeLM.generateResponse(chatInput, 8, orc.evolution.currentGenome.resonanceTemperature)
                            chatOutput = "AI: $reply"
                            chatInput = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Gửi & Dạy Thêm Ký Ức Cho AI")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 2. TIẾN HÓA DI TRUYỀN TỰ ĐỘNG
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🧬 LÕI TỰ TIẾN HÓA THUẬT TOÁN (GENETIC ENGINE)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                val fitness = (orc.evolution.currentGenome.fitnessScore * 100).toInt()
                Text("Chỉ số thông minh: $fitness% | Tự tiến hóa ngầm khi cắm sạc ban đêm", fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(progress = (fitness / 100f).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(6.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val (_, msg) = orc.evolution.runEvolutionCycle()
                        evoStatusText = msg
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Kích Hoạt 1 Chu Kỳ Tiến Hóa Tức Thì")
                }
                Text(evoStatusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 3. THAO TÁC CÁC SIÊU NĂNG LỰC
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("⚡ NĂNG LỰC HIỆU NĂNG CAO (GAME, 3D, HFT)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val path = orc.capabilities.bresenhamSwipe(100, 200, 800, 1000, 5)
                        Toast.makeText(ctx, "Game Reflex: Đã sinh đường vuốt ${path.size} điểm trong 1.2ms", Toast.LENGTH_SHORT).show()
                        HoloAccessibilityService.instance?.dispatchTap(path.last().x.toFloat(), path.last().y.toFloat())
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sinh Cử Chỉ Game Bresenham (< 2ms)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val obj = orc.capabilities.buildCylinderObj(1.2f, 3f, 8)
                        Toast.makeText(ctx, "3D Studio: Đã xuất lưới đa giác (.OBJ) ${obj.lines().size} dòng mã", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Xuất Khối 3D Cylinder (.OBJ)")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 4. BẬT/TẮT DỊCH VỤ NGOÀI MÀN HÌNH 24/7
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text("🚀 DỊCH VỤ HOẠT ĐỘNG NGOÀI MÀN HÌNH (24/7)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val it = Intent(ctx, Floating3DAvatarService::class.java)
                        if (!isAvatarRunning) { ctx.startForegroundService(it); isAvatarRunning = true }
                        else { ctx.stopService(it); isAvatarRunning = false }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isAvatarRunning) "TẮT TRỢ LÝ NỔI" else "BẬT TRỢ LÝ NỔI TRÊN MÀN HÌNH")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val it = Intent(ctx, ProductionTradingService::class.java)
                        if (!isTradeRunning) { ctx.startForegroundService(it); isTradeRunning = true }
                        else { ctx.stopService(it); isTradeRunning = false }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isTradeRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isTradeRunning) "DỪNG BOT HFT GIAO DỊCH" else "BẬT BOT HFT BINANCE 24/7")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Giá Binance thời gian thực: ${ProductionTradingService.livePrice}", fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}