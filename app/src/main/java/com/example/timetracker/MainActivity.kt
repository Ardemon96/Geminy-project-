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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    var timerService by mutableStateOf<TimerService?>(null)
        private set
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
    var selectedCategory by remember { mutableStateOf("Работа") }

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
                0 -> TimerTab(
                    timerServiceProvider = timerServiceProvider,
                    defaultCategories = defaultCategories,
                    customCategories = customCategories,
                    dao = dao,
                    selectedCategory = selectedCategory,
                    onCategorySelected = { selectedCategory = it }
                )
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
    defaultCategories: List<String>,
    customCategories: List<CategoryEntity>,
    dao: com.example.timetracker.data.ActivityDao,
    selectedCategory: String,
    onCategorySelected: (String) -> Unit
) {
    val context = LocalContext.current
    val timerService = timerServiceProvider()

    var activityTitle by remember { mutableStateOf("") }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var newCategoryInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val allCategories = (defaultCategories + customCategories.map { it.name }).distinct()

    val timeInSeconds by timerService?.timeInSeconds?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val isRunning by timerService?.isRunning?.collectAsState() ?: remember { mutableStateOf(false) }

    // Синхронизируем поля при подключении сервиса или возобновлении экрана если таймер активен
    LaunchedEffect(timerService?.currentTitle, timerService?.currentCategory) {
        timerService?.let {
            if (it.currentTitle.isNotBlank()) {
                activityTitle = it.currentTitle
            }
            if (it.currentCategory.isNotBlank()) {
                onCategorySelected(it.currentCategory)
            }
        }
    }

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
                TextButton(onClick = { showCategoryDialog = true }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Управление")
                }
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(allCategories) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onCategorySelected(cat) },
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

    if (showCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("Управление категориями") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                ) {
                    OutlinedTextField(
                        value = newCategoryInput,
                        onValueChange = { newCategoryInput = it },
                        label = { Text("Новая категория") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (newCategoryInput.isNotBlank()) {
                                val trimmed = newCategoryInput.trim()
                                if (!allCategories.contains(trimmed)) {
                                    scope.launch {
                                        dao.insertCategory(CategoryEntity(trimmed))
                                        onCategorySelected(trimmed)
                                        newCategoryInput = ""
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Добавить")
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Пользовательские категории:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (customCategories.isEmpty()) {
                        Text("Нет пользовательских категорий", color = Color.Gray, fontSize = 12.sp)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(customCategories) { customCat ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(customCat.name, style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = {
                                        scope.launch {
                                            dao.deleteCategory(customCat.name)
                                            if (selectedCategory == customCat.name) {
                                                onCategorySelected(defaultCategories.first())
                                            }
                                        }
                                    }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Удалить",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCategoryDialog = false }) {
                    Text("Готово")
                }
            }
        )
    }
}

enum class TimePeriod {
    ALL_TIME, DAY, WEEK
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsTab(logs: List<ActivityLog>) {
    var selectedPeriod by remember { mutableStateOf(TimePeriod.DAY) }
    var selectedCalendar by remember { mutableStateOf(Calendar.getInstance()) }

    val dateFormat = remember { SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()) }
    val weekFormat = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }

    val filteredLogs = remember(logs, selectedPeriod, selectedCalendar.timeInMillis) {
        when (selectedPeriod) {
            TimePeriod.ALL_TIME -> logs
            TimePeriod.DAY -> {
                val startOfDay = (selectedCalendar.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val endOfDay = (selectedCalendar.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis

                logs.filter { it.timestamp in startOfDay..endOfDay }
            }
            TimePeriod.WEEK -> {
                val startOfWeek = (selectedCalendar.clone() as Calendar).apply {
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                val endOfWeek = (selectedCalendar.clone() as Calendar).apply {
                    firstDayOfWeek = Calendar.MONDAY
                    set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis

                logs.filter { it.timestamp in startOfWeek..endOfWeek }
            }
        }
    }

    val totalSeconds = filteredLogs.sumOf { it.durationSeconds }
    val categoryGrouped = filteredLogs.groupBy { it.category }
        .mapValues { entry -> entry.value.sumOf { it.durationSeconds } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Аналитика", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = selectedPeriod == TimePeriod.DAY,
                onClick = { selectedPeriod = TimePeriod.DAY },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
            ) {
                Text("День")
            }
            SegmentedButton(
                selected = selectedPeriod == TimePeriod.WEEK,
                onClick = { selectedPeriod = TimePeriod.WEEK },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
            ) {
                Text("Неделя")
            }
            SegmentedButton(
                selected = selectedPeriod == TimePeriod.ALL_TIME,
                onClick = { selectedPeriod = TimePeriod.ALL_TIME },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
            ) {
                Text("Всё время")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedPeriod != TimePeriod.ALL_TIME) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val newCal = selectedCalendar.clone() as Calendar
                    if (selectedPeriod == TimePeriod.DAY) {
                        newCal.add(Calendar.DAY_OF_YEAR, -1)
                    } else {
                        newCal.add(Calendar.WEEK_OF_YEAR, -1)
                    }
                    selectedCalendar = newCal
                }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Назад")
                }

                val titleText = if (selectedPeriod == TimePeriod.DAY) {
                    dateFormat.format(selectedCalendar.time)
                } else {
                    val start = (selectedCalendar.clone() as Calendar).apply {
                        firstDayOfWeek = Calendar.MONDAY
                        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    }
                    val end = (selectedCalendar.clone() as Calendar).apply {
                        firstDayOfWeek = Calendar.MONDAY
                        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
                    }
                    "${weekFormat.format(start.time)} — ${weekFormat.format(end.time)}"
                }

                Text(titleText, style = MaterialTheme.typography.titleMedium)

                IconButton(onClick = {
                    val newCal = selectedCalendar.clone() as Calendar
                    if (selectedPeriod == TimePeriod.DAY) {
                        newCal.add(Calendar.DAY_OF_YEAR, 1)
                    } else {
                        newCal.add(Calendar.WEEK_OF_YEAR, 1)
                    }
                    selectedCalendar = newCal
                }) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Вперед")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

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

        Spacer(modifier = Modifier.height(16.dp))
        Text("Распределение по категориям:", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))

        if (totalSeconds == 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("За выбранный период нет данных", color = Color.Gray)
            }
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
                            val catH = seconds / 3600
                            val catM = (seconds % 3600) / 60
                            val timeStr = if (catH > 0) "${catH}ч ${catM}м" else "${catM} мин"
                            Text("${(percentage * 100).toInt()}% ($timeStr)")
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
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

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
                                val dateStr = dateFormat.format(Date(log.timestamp))
                                Text("${log.category} • ${log.durationSeconds / 60} мин\n$dateStr", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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