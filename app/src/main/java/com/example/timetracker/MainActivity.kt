package com.example.timetracker

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.timetracker.data.ActivityLog
import com.example.timetracker.data.AppDatabase
import com.example.timetracker.data.CategoryEntity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var timerService: TimerService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as TimerService.TimerBinder
            timerService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            timerService = null
        }
    }

    override fun onStart() {
        super.onStart()
        Intent(this, TimerService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Запрос разрешения на показ уведомления (для Android 13+)
                    RequestNotificationPermission()
                    MainScreen(timerServiceProvider = { timerService })
                }
            }
        }
    }
}

@Composable
fun RequestNotificationPermission() {
    val context = LocalContext.current
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
            onResult = { isGranted -> hasPermission = isGranted }
        )

        LaunchedEffect(Unit) {
            if (!hasPermission) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(timerServiceProvider: () -> TimerService?) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = db.activityDao()

    val logs by dao.getAllLogs().collectAsState(initial = emptyList())
    val customCategories by dao.getAllCategories().collectAsState(initial = emptyList())

    val defaultCategories = listOf("Работа", "Чтение", "Развлечения", "Учеба/Программирование", "Быт", "Отдых")
    val allCategories = (defaultCategories + customCategories.map { it.name }).distinct()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Tracker") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Timer, contentDescription = null) },
                    label = { Text("Таймер") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    label = { Text("Графики") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("История") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> TimerTab(timerServiceProvider, allCategories, dao)
                1 -> AnalyticsTab(logs)
                2 -> HistoryTab(logs, dao)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerTab(
    timerServiceProvider: () -> TimerService?,
    categories: List<String>,
    dao: com.example.timetracker.data.ActivityDao
) {
    val context = LocalContext.current
    val timerService = timerServiceProvider()

    var activityTitle by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "Работа") }

    val timeInSeconds by timerService?.timeInSeconds?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val isRunning by timerService?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }

    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val hours = timeInSeconds / 3600
    val minutes = (timeInSeconds % 3600) / 60
    val seconds = timeInSeconds % 60
    val formattedTime = String.format("%02d:%02d:%02d", hours, minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            OutlinedTextField(
                value = activityTitle,
                onValueChange = { activityTitle = it },
                label = { Text("Название активности") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Категория:", style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { showAddCategoryDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Добавить категорию")
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        label = { Text(cat, fontSize = 12.sp) }
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Box(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formattedTime,
                    fontSize = 54.sp,
                    style = MaterialTheme.typography.displayLarge
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            Button(
                onClick = {
                    val intent = Intent(context, TimerService::class.java)
                    if (isRunning) {
                        timerService?.pauseTimer()
                    } else {
                        intent.action = TimerService.ACTION_START
                        intent.putExtra(TimerService.EXTRA_TITLE, activityTitle)
                        intent.putExtra(TimerService.EXTRA_CATEGORY, selectedCategory)
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isRunning) "Пауза" else "Старт")
            }

            OutlinedButton(
                onClick = {
                    timerService?.stopAndSaveTimer()
                    activityTitle = ""
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Сохранить")
            }
        }
    }

    if (showAddCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("Новая категория") },
            text = {
                OutlinedTextField(
                    value = newCategoryInput,
                    onValueChange = { newCategoryInput = it },
                    label = { Text("Название категории") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newCategoryInput.isNotBlank()) {
                        scope.launch {
                            dao.insertCategory(CategoryEntity(newCategoryInput.trim()))
                            selectedCategory = newCategoryInput.trim()
                            newCategoryInput = ""
                            showAddCategoryDialog = false
                        }
                    }
                }) {
                    Text("Добавить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCategoryDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun AnalyticsTab(logs: List<ActivityLog>) {
    val totalSeconds = logs.sumOf { it.durationSeconds }
    val categoryGrouped = logs.groupBy { it.category }
        .mapValues { entry -> entry.value.sumOf { it.durationSeconds } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Аналитика и Графики", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Общее зафиксированное время:")
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                Text("${h}ч ${m}мин", style = MaterialTheme.typography.headlineMedium)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Распределение по категориям:", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))

        if (totalSeconds == 0L) {
            Text("Нет данных для отображения графиков", color = Color.Gray)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(categoryGrouped.toList()) { (category, seconds) ->
                    val percentage = seconds.toFloat() / totalSeconds.toFloat()
                    val animatedWidth by animateFloatAsState(targetValue = percentage, label = "bar")

                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(category, style = MaterialTheme.typography.bodyMedium)
                            Text("${(percentage * 100).toInt()}% (${seconds / 60} мин)")
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedWidth)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryTab(logs: List<ActivityLog>, dao: com.example.timetracker.data.ActivityDao) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("История активности", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("История пуста")
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs) { log ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(log.title, style = MaterialTheme.typography.titleMedium)
                                Text("${log.category} • ${log.durationSeconds / 60} мин", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            IconButton(onClick = {
                                scope.launch { dao.deleteLog(log.id) }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}