package com.example.timetracker

import androidx.compose.ui.unit.sp
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.*

@Entity(tableName = "activity_logs")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val durationSeconds: Long,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ActivityLogDao {
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<ActivityLog>>

    @Insert
    suspend fun insertLog(log: ActivityLog)

    @Delete
    suspend fun deleteLog(log: ActivityLog)
}

@Database(entities = [ActivityLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "time_tracker_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).activityLogDao()

    val allLogs: StateFlow<List<ActivityLog>> = dao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveLog(title: String, category: String, durationSeconds: Long) {
        if (durationSeconds <= 0) return
        viewModelScope.launch {
            dao.insertLog(
                ActivityLog(
                    title = title.ifBlank { "Без названия" },
                    category = category,
                    durationSeconds = durationSeconds
                )
            )
        }
    }

    fun deleteLog(log: ActivityLog) {
        viewModelScope.launch {
            dao.deleteLog(log)
        }
    }
}

enum class StatsPeriod(val label: String) {
    DAY("День"),
    WEEK("Неделя"),
    MONTH("Месяц")
}

data class ChartBarData(
    val label: String,
    val totalSeconds: Long
)

fun groupLogsByPeriod(logs: List<ActivityLog>, period: StatsPeriod): List<ChartBarData> {
    val calendar = Calendar.getInstance()
    val now = System.currentTimeMillis()

    return when (period) {
        StatsPeriod.DAY -> {
            val buckets = listOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00")
            val totals = LongArray(6) { 0L }
            val startOfDay = calendar.apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            logs.filter { it.timestamp >= startOfDay }.forEach { log ->
                calendar.timeInMillis = log.timestamp
                val hour = calendar.get(Calendar.HOUR_OF_DAY)
                val bucketIndex = (hour / 4).coerceIn(0, 5)
                totals[bucketIndex] += log.durationSeconds
            }
            buckets.mapIndexed { idx, label -> ChartBarData(label, totals[idx]) }
        }
        StatsPeriod.WEEK -> {
            val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
            val totals = LongArray(7) { 0L }
            val sevenDaysAgo = now - 7 * 24 * 3600 * 1000L

            logs.filter { it.timestamp >= sevenDaysAgo }.forEach { log ->
                calendar.timeInMillis = log.timestamp
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val idx = if (dayOfWeek == Calendar.SUNDAY) 6 else dayOfWeek - 2
                if (idx in 0..6) {
                    totals[idx] += log.durationSeconds
                }
            }
            days.mapIndexed { idx, label -> ChartBarData(label, totals[idx]) }
        }
        StatsPeriod.MONTH -> {
            val weeks = listOf("Нед 1", "Нед 2", "Нед 3", "Нед 4")
            val totals = LongArray(4) { 0L }
            val thirtyDaysAgo = now - 30 * 24 * 3600 * 1000L

            logs.filter { it.timestamp >= thirtyDaysAgo }.forEach { log ->
                val diffDays = ((now - log.timestamp) / (24 * 3600 * 1000L)).toInt()
                val weekIdx = (3 - (diffDays / 7)).coerceIn(0, 3)
                totals[weekIdx] += log.durationSeconds
            }
            weeks.mapIndexed { idx, label -> ChartBarData(label, totals[idx]) }
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            val accentColor = Color(0xFF0A84FF)
            var currentScreen by remember { mutableStateOf("main") }
            val logs by viewModel.allLogs.collectAsState()

            MaterialTheme(colorScheme = darkColorScheme(background = Color.Black)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    if (currentScreen == "stats") {
                        StatsScreen(
                            logs = logs,
                            accentColor = accentColor,
                            onBackClick = { currentScreen = "main" }
                        )
                    } else {
                        MainTrackerScreen(
                            logs = logs,
                            accentColor = accentColor,
                            onOpenStats = { currentScreen = "stats" },
                            onSaveLog = { title, cat, sec -> viewModel.saveLog(title, cat, sec) },
                            onDeleteLog = { viewModel.deleteLog(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainTrackerScreen(
    logs: List<ActivityLog>,
    accentColor: Color,
    onOpenStats: () -> Unit,
    onSaveLog: (String, String, Long) -> Unit,
    onDeleteLog: (ActivityLog) -> Unit
) {
    var isRunning by remember { mutableStateOf(false) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var taskTitle by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Развитие") }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(1000L)
            elapsedSeconds++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Таймер активности", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = onOpenStats,
                modifier = Modifier.background(Color(0xFF1C1C1E), CircleShape)
            ) {
                Icon(Icons.Default.BarChart, contentDescription = "Статистика", tint = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val hours = elapsedSeconds / 3600
                val minutes = (elapsedSeconds % 3600) / 60
                val seconds = elapsedSeconds % 60
                val timeString = String.format("%02d:%02d:%02d", hours, minutes, seconds)

                Text(timeString, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = taskTitle,
                    onValueChange = { taskTitle = it },
                    placeholder = { Text("Чем вы заняты?", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color(0xFF2C2C2E),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Развитие", "Досуг").forEach { category ->
                        val isSel = selectedCategory == category
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .background(
                                    if (isSel) accentColor else Color(0xFF2C2C2E),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedCategory = category },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(category, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { isRunning = !isRunning },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRunning) Color(0xFFFF453A) else accentColor
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f).height(50.dp)
                    ) {
                        Text(if (isRunning) "Пауза" else "Старт", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    if (elapsedSeconds > 0) {
                        Button(
                            onClick = {
                                onSaveLog(taskTitle, selectedCategory, elapsedSeconds)
                                elapsedSeconds = 0L
                                isRunning = false
                                taskTitle = ""
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF30D158)),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.height(50.dp)
                        ) {
                            Text("Сохранить", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("История записей", color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(logs) { log ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(log.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text("${log.category} • ${log.durationSeconds / 60} мин", color = Color.Gray, fontSize = 13.sp)
                        }
                        IconButton(onClick = { onDeleteLog(log) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Color.DarkGray)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatsScreen(
    logs: List<ActivityLog>,
    accentColor: Color,
    onBackClick: () -> Unit
) {
    var selectedPeriod by remember { mutableStateOf(StatsPeriod.WEEK) }
    var showDevelopment by remember { mutableStateOf(true) }
    var showLeisure by remember { mutableStateOf(true) }

    val filteredLogs = remember(logs, showDevelopment, showLeisure) {
        logs.filter { log ->
            (showDevelopment && log.category == "Развитие") ||
            (showLeisure && log.category == "Досуг")
        }
    }

    val chartData = remember(filteredLogs, selectedPeriod) {
        groupLogsByPeriod(filteredLogs, selectedPeriod)
    }

    val totalSeconds = remember(chartData) { chartData.sumOf { it.totalSeconds } }
    val totalHours = totalSeconds / 3600
    val totalMinutes = (totalSeconds % 3600) / 60

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.background(Color(0xFF1C1C1E), CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text("Статистика", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            StatsPeriod.values().forEach { period ->
                val isSelected = selectedPeriod == period
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(
                            if (isSelected) Color(0xFF2C2C2E) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { selectedPeriod = period },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = period.label,
                        color = if (isSelected) Color.White else Color.Gray,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1E))
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Всего затрачено", color = Color.Gray, fontSize = 13.sp)
                Text("$totalHours ч $totalMinutes мин", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(24.dp))

                BarChart(
                    data = chartData,
                    accentColor = accentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Фильтр категорий", color = Color.Gray, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChipCategory(
                label = "Развитие",
                isSelected = showDevelopment,
                color = accentColor,
                modifier = Modifier.weight(1f)
            ) { showDevelopment = !showDevelopment }

            FilterChipCategory(
                label = "Досуг",
                isSelected = showLeisure,
                color = Color(0xFF8E8E93),
                modifier = Modifier.weight(1f)
            ) { showLeisure = !showLeisure }
        }
    }
}

@Composable
fun BarChart(
    data: List<ChartBarData>,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Нет данных за выбранный период", color = Color.Gray, fontSize = 14.sp)
        }
        return
    }

    val maxSeconds = remember(data) { (data.maxOfOrNull { it.totalSeconds } ?: 1L).coerceAtLeast(1L) }
    val progress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 600),
        label = "chartAnim"
    )

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val width = size.width
            val height = size.height
            val barWidth = (width / (data.size * 2)).coerceAtMost(36.dp.toPx())
            val spacing = (width - (barWidth * data.size)) / (data.size + 1)

            for (i in 0..3) {
                val y = height * (i.toFloat() / 3)
                drawLine(
                    color = Color.DarkGray.copy(alpha = 0.3f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                )
            }

            data.forEachIndexed { index, item ->
                val barHeight = (item.totalSeconds.toFloat() / maxSeconds) * height * progress
                val x = spacing + index * (barWidth + spacing)
                val y = height - barHeight

                drawRoundRect(
                    color = Color(0xFF2C2C2E),
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                )

                if (barHeight > 0) {
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            data.forEach { item ->
                Text(item.label, color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun FilterChipCategory(
    label: String,
    isSelected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .background(
                if (isSelected) color.copy(alpha = 0.2f) else Color(0xFF1C1C1E),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(12.dp).background(if (isSelected) color else Color.Gray, CircleShape))
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, color = if (isSelected) Color.White else Color.Gray, fontWeight = FontWeight.Medium, fontSize = 15.sp)
        }
    }
}
