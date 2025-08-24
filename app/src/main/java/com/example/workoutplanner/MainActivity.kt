
package com.example.workoutplanner

import android.Manifest
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import java.time.*
import java.time.format.DateTimeFormatter
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.work.*
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private val ComponentActivity.dataStore by preferencesDataStore(name = "workout_store")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current as ComponentActivity

    var currentMonth by remember { mutableStateOf(YearMonth.of(2025, 9)) }
    var plan by remember { mutableStateOf(generatePlan(currentMonth, defaultWorkDays(currentMonth))) }

    // Persisted checkbox/RPE/weight state
    val checkedSets = remember { mutableStateMapOf<String, Boolean>() }
    val weights = remember { mutableStateMapOf<String, String>() } // key->kg
    val rpes = remember { mutableStateMapOf<String, String>() } // key->RPE

    // Notification permission launcher (Android 13+)
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(currentMonth) {
        val prefs = ctx.dataStore.data.first()
        checkedSets.clear(); weights.clear(); rpes.clear()
        prefs.asMap().forEach { (k, v) ->
            when {
                k is Preferences.Key<*> && v is Boolean -> checkedSets[k.name] = v
                k is Preferences.Key<*> && k.name.endsWith("|w") && v is String -> weights[k.name] = v
                k is Preferences.Key<*> && k.name.endsWith("|r") && v is String -> rpes[k.name] = v
            }
        }
    }

    fun toggle(key: String, value: Boolean) {
        scope.launch { ctx.dataStore.edit { it[booleanPreferencesKey(key)] = value }; checkedSets[key] = value }
    }
    fun saveWeight(key: String, value: String) {
        scope.launch { ctx.dataStore.edit { it[stringPreferencesKey("${key}|w")] = value }; weights["${key}|w"] = value }
    }
    fun saveRpe(key: String, value: String) {
        scope.launch { ctx.dataStore.edit { it[stringPreferencesKey("${key}|r")] = value }; rpes["${key}|r"] = value }
    }

    // Daily reminder controls
    var reminderEnabled by remember { mutableStateOf(false) }
    var reminderTime by remember { mutableStateOf(LocalTime.of(19, 0)) }

    fun scheduleDailyReminder(time: LocalTime) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val now = LocalDateTime.now()
        var first = LocalDateTime.of(LocalDate.now(), time)
        if (first.isBefore(now)) first = first.plusDays(1)
        val delay = Duration.between(now, first).toMinutes()
        val data = workDataOf(
            "title" to "Workout reminder",
            "message" to todaySummary(plan)
        )
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setInputData(data)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "daily_workout_reminder",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    // Export to CSV
    val createCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) exportToCsv(ctx.contentResolver, uri, plan, weights, rpes, checkedSets)
    }

    var tabIndex by remember { mutableStateOf(0) }

    MaterialTheme(colorScheme = lightColorScheme()) {
        Scaffold(topBar = { TopAppBar(title = { Text("Workout Planner") }) }) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Month:", fontWeight = FontWeight.Bold)
                    AssistChip(onClick = {
                        currentMonth = YearMonth.of(2025, 8); plan = generateAugRange()
                    }, label = { Text("Aug 22–31, 2025") })
                    AssistChip(onClick = {
                        currentMonth = YearMonth.of(2025, 9); plan = generatePlan(currentMonth, defaultWorkDays(currentMonth))
                    }, label = { Text("September 2025") })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { createCsvLauncher.launch("workout_log.csv") }) { Icon(Icons.Filled.FileDownload, contentDescription = "Export CSV") }
                }

                Spacer(Modifier.height(8.dp))

                Card { Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Notifications, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Daily reminder at ${reminderTime.format(DateTimeFormatter.ofPattern("HH:mm"))}")
                        }
                        Switch(checked = reminderEnabled, onCheckedChange = { on ->
                            reminderEnabled = on
                            if (on) scheduleDailyReminder(reminderTime) else WorkManager.getInstance(ctx).cancelUniqueWork("daily_workout_reminder")
                        })
                    }
                    if (reminderEnabled) {
                        Spacer(Modifier.height(8.dp))
                        TimePickerRow(reminderTime) { newTime -> reminderTime = newTime; scheduleDailyReminder(newTime) }
                    }
                }}

                Spacer(Modifier.height(8.dp))

                TabRow(selectedTabIndex = tabIndex) {
                    Tab(selected = tabIndex == 0, onClick = { tabIndex = 0 }, text = { Text("Plan") })
                    Tab(selected = tabIndex == 1, onClick = { tabIndex = 1 }, text = { Text("Summary") })
                }

                when (tabIndex) {
                    0 -> PlanScreen(currentMonth, plan, checkedSets, weights, rpes, onToggle = ::toggle, onSaveWeight = ::saveWeight, onSaveRpe = ::saveRpe)
                    1 -> SummaryScreen(plan, weights, rpes)
                }
            }
        }
    }
}

@Composable
fun PlanScreen(
    currentMonth: YearMonth,
    plan: WorkoutPlan,
    checkedSets: Map<String, Boolean>,
    weights: Map<String, String>,
    rpes: Map<String, String>,
    onToggle: (String, Boolean) -> Unit,
    onSaveWeight: (String, String) -> Unit,
    onSaveRpe: (String, String) -> Unit
) {
    Text("Tap a day to expand. Tick a checkbox per set. Enter Weight & RPE. Use Rest Timer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(plan.days) { d ->
            DayCard(day = d, checkedSets = checkedSets, weights = weights, rpes = rpes,
                onToggle = onToggle, onSaveWeight = onSaveWeight, onSaveRpe = onSaveRpe)
        }
    }
}

@Composable
fun SummaryScreen(plan: WorkoutPlan, weights: Map<String, String>, rpes: Map<String, String>) {
    val today = LocalDate.now()
    val weekStart = today.with(DayOfWeek.MONDAY)
    val weekEnd = weekStart.plusDays(6)

    val entries = plan.days.filter { !it.date.isBefore(weekStart) && !it.date.isAfter(weekEnd) }
        .flatMap { day ->
            day.items.flatMap { ex ->
                (1..ex.sets).map { setIdx ->
                    val base = "${day.date}|${ex.name}|set$setIdx"
                    Triple(ex.name, weights["${base}|w"]?.toDoubleOrNull() ?: 0.0, rpes["${base}|r"]?.toDoubleOrNull() ?: 0.0)
                }
            }
        }

    val byExercise = entries.groupBy { it.first }
    val rows = byExercise.map { (name, list) ->
        val best = list.maxOfOrNull { it.second } ?: 0.0
        val avgRpe = if (list.isNotEmpty()) list.map { it.third }.filter { it > 0 }.average() else 0.0
        val estNext = recommendNextLoad(best, avgRpe)
        SummaryRow(name, bestKg = best, avgRpe = if (avgRpe.isNaN()) 0.0 else avgRpe, nextKg = estNext)
    }.sortedBy { it.name }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This Week ($weekStart to $weekEnd)", fontWeight = FontWeight.Bold)
        if (rows.isEmpty()) Text("No data yet. Log some sets to see your summary.")
        rows.forEach { r ->
            Card { Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontWeight = FontWeight.SemiBold)
                    Text("Best: ${"%.1f".format(r.bestKg)} kg | Avg RPE: ${"%.1f".format(r.avgRpe)}")
                }
                Text("Next: ${if (r.nextKg>0) "${"%.1f".format(r.nextKg)} kg" else "repeat"}", fontWeight = FontWeight.Bold)
            }}
        }
    }
}

data class SummaryRow(val name: String, val bestKg: Double, val avgRpe: Double, val nextKg: Double)

fun recommendNextLoad(bestKg: Double, avgRpe: Double): Double {
    return when {
        bestKg <= 0.0 -> 0.0
        avgRpe <= 7.0 -> bestKg + 2.5
        avgRpe <= 8.5 -> bestKg + 1.0
        else -> bestKg
    }
}

@Composable
fun TimePickerRow(current: LocalTime, onChange: (LocalTime) -> Unit) {
    var hour by remember { mutableStateOf(current.hour) }
    var minute by remember { mutableStateOf(current.minute) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = hour.toString().padStart(2, '0'),
            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..23) { hour = it; onChange(LocalTime.of(hour, minute)) } } },
            label = { Text("HH") }, singleLine = true, modifier = Modifier.width(80.dp))
        OutlinedTextField(value = minute.toString().padStart(2, '0'),
            onValueChange = { v -> v.toIntOrNull()?.let { if (it in 0..59) { minute = it; onChange(LocalTime.of(hour, minute)) } } },
            label = { Text("MM") }, singleLine = true, modifier = Modifier.width(80.dp))
    }
}

@Composable
fun DayCard(
    day: PlanDay,
    checkedSets: Map<String, Boolean>,
    weights: Map<String, String>,
    rpes: Map<String, String>,
    onToggle: (String, Boolean) -> Unit,
    onSaveWeight: (String, String) -> Unit,
    onSaveRpe: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }) {
        Column(Modifier.padding(12.dp)) {
            Text("${day.date} — ${day.summary}", fontWeight = FontWeight.Bold)
            if (expanded && day.items.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                RestTimer()
                Spacer(Modifier.height(8.dp))
                day.items.forEach { item ->
                    ExerciseItemRow(day.date.toString(), item, checkedSets, weights, rpes, onToggle, onSaveWeight, onSaveRpe)
                    Spacer(Modifier.height(6.dp))
                }
                val hints = day.items.filter { it.name.startsWith("Barbell") || it.name.contains("Squat") || it.name.contains("Deadlift") }
                if (hints.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Card { Column(Modifier.padding(12.dp)) {
                        Text("Progression tips", fontWeight = FontWeight.SemiBold)
                        hints.forEach { ex ->
                            val best = (1..ex.sets).maxOfOrNull { setIdx ->
                                val base = "${day.date}|${ex.name}|set$setIdx|w"
                                weights[base]?.toDoubleOrNull() ?: 0.0
                            } ?: 0.0
                            val avgRpe = (1..ex.sets).mapNotNull { setIdx -> rpes["${day.date}|${ex.name}|set$setIdx|r"]?.toDoubleOrNull() }.average()
                            val next = recommendNextLoad(best, if (avgRpe.isNaN()) 0.0 else avgRpe)
                            Text("${ex.name}: ${if (next>0 && next>best) "Try ~${"%.1f".format(next)} kg next time" else "Repeat best ${"%.1f".format(best)} kg"}")
                        }
                    }}
                }
            } else if (expanded) {
                Text("No workout (work/rest)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RestTimer() {
    var seconds by remember { mutableStateOf(60) }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startTimer(duration: Int) {
        seconds = duration; running = true
        scope.launch {
            while (running && seconds > 0) {
                kotlinx.coroutines.delay(1000)
                seconds--
            }
            running = false
        }
    }

    Card { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Rest Timer: ${seconds}s")
        Button(onClick = { startTimer(60) }) { Text("60s") }
        Button(onClick = { startTimer(90) }) { Text("90s") }
        OutlinedButton(onClick = { running = false }) { Text("Stop") }
    }}
}

@Composable
fun ExerciseItemRow(
    dateKey: String,
    item: ExerciseItem,
    checked: Map<String, Boolean>,
    weights: Map<String, String>,
    rpes: Map<String, String>,
    onToggle: (String, Boolean) -> Unit,
    onSaveWeight: (String, String) -> Unit,
    onSaveRpe: (String, String) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(item.name, fontWeight = FontWeight.SemiBold)
        Text(item.detail ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(item.sets) { idx ->
                val baseKey = "$dateKey|${item.name}|set${idx+1}"
                val isChecked = checked[baseKey] == true
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AssistChip(onClick = { onToggle(baseKey, !isChecked) }, label = { Text("Set ${idx+1}") }, leadingIcon = { if (isChecked) Icon(Icons.Filled.Check, contentDescription = null) }, selected = isChecked)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(value = weights["${baseKey}|w"] ?: "", onValueChange = { onSaveWeight(baseKey, it) }, label = { Text("kg") }, singleLine = true, modifier = Modifier.width(90.dp))
                        OutlinedTextField(value = rpes["${baseKey}|r"] ?: "", onValueChange = { onSaveRpe(baseKey, it) }, label = { Text("RPE") }, singleLine = true, modifier = Modifier.width(90.dp))
                    }
                }
            }
        }
    }
}

// ---------- Data models & plan generation ----------
@Serializable
enum class WorkoutType { STRENGTH_A, STRENGTH_B, CARDIO, YOGA, REST }

@Serializable
data class ExerciseItem(val name: String, val sets: Int = 3, val detail: String? = null)

@Serializable
data class PlanDay(val date: LocalDate, val type: WorkoutType, val summary: String, val items: List<ExerciseItem> = emptyList())

@Serializable
data class WorkoutPlan(val month: YearMonth, val workDays: Set<Int>, val days: List<PlanDay>)

fun generatePlan(month: YearMonth, workDays: Set<Int>): WorkoutPlan {
    val cycle = listOf(WorkoutType.STRENGTH_A, WorkoutType.CARDIO, WorkoutType.STRENGTH_B, WorkoutType.YOGA)
    var idx = 0
    val days = (1..month.lengthOfMonth()).map { d ->
        val date = LocalDate.of(month.year, month.monthValue, d)
        if (workDays.contains(d)) PlanDay(date, WorkoutType.REST, "Work/Rest") else {
            val type = cycle[idx % cycle.size]; idx++; PlanDay(date, type, type.toLabel(), type.toExercises())
        }
    }
    return WorkoutPlan(month, workDays, days)
}

fun generateAugRange(): WorkoutPlan {
    val month = YearMonth.of(2025, 8)
    val schedule = mapOf(
        22 to WorkoutType.STRENGTH_A,
        23 to WorkoutType.YOGA,
        24 to WorkoutType.CARDIO,
        25 to WorkoutType.STRENGTH_B,
        26 to WorkoutType.YOGA,
        27 to WorkoutType.STRENGTH_A,
        28 to WorkoutType.CARDIO,
        29 to WorkoutType.STRENGTH_B,
        30 to WorkoutType.YOGA,
        31 to WorkoutType.STRENGTH_A,
    )
    val days = (1..month.lengthOfMonth()).map { d ->
        val date = LocalDate.of(month.year, month.monthValue, d)
        val type = schedule[d]
        if (type == null) PlanDay(date, WorkoutType.REST, "—") else PlanDay(date, type, type.toLabel(), type.toExercises())
    }
    return WorkoutPlan(month, emptySet(), days)
}

fun defaultWorkDays(month: YearMonth): Set<Int> = when (month) {
    YearMonth.of(2025, 9) -> setOf(8, 9, 12, 15, 16, 17, 19, 23, 26)
    else -> emptySet()
}

fun WorkoutType.toLabel(): String = when (this) {
    WorkoutType.STRENGTH_A -> "Strength A (Deadlift + Pull)"
    WorkoutType.STRENGTH_B -> "Strength B (Squat + Push)"
    WorkoutType.CARDIO -> "Cardio"
    WorkoutType.YOGA -> "Yoga / Recovery"
    WorkoutType.REST -> "Work/Rest"
}

fun WorkoutType.toExercises(): List<ExerciseItem> = when (this) {
    WorkoutType.STRENGTH_A -> listOf(
        ExerciseItem("Warm-up: bike/treadmill", sets = 1, detail = "5 min"),
        ExerciseItem("Barbell Deadlift", 3, "3×8 reps — light to moderate"),
        ExerciseItem("Dumbbell Bench Press", 3, "3×10 reps"),
        ExerciseItem("One-arm Dumbbell Row", 3, "3×10 each side"),
        ExerciseItem("Assisted Pull-ups / Band", 3, "Max reps"),
        ExerciseItem("Bodyweight Squats", 3, "3×12 reps"),
        ExerciseItem("Plank", 3, "3×30 sec")
    )
    WorkoutType.STRENGTH_B -> listOf(
        ExerciseItem("Warm-up: bike/treadmill", sets = 1, detail = "5 min"),
        ExerciseItem("Barbell Back Squat", 3, "3×8 reps — light to moderate"),
        ExerciseItem("Dumbbell Shoulder Press", 3, "3×10 reps"),
        ExerciseItem("Leg Curl Machine", 3, "3×12 reps"),
        ExerciseItem("Dumbbell RDL", 3, "3×10 reps"),
        ExerciseItem("Push-ups (knees if needed)", 3, "Max reps"),
        ExerciseItem("Dead Bugs", 3, "3×10 each side")
    )
    WorkoutType.CARDIO -> listOf(
        ExerciseItem("Peloton ride OR Treadmill intervals", 1, "20–30 min"),
        ExerciseItem("Mobility finisher", 1, "5–10 min: cat-cow, hip openers, shoulder rolls")
    )
    WorkoutType.YOGA -> listOf(
        ExerciseItem("Yoga (home/Peloton)", 1, "30 min focus: flexibility & breath")
    )
    WorkoutType.REST -> emptyList()
}

fun todaySummary(plan: WorkoutPlan): String {
    val today = LocalDate.now()
    val found = plan.days.find { it.date == today }
    return found?.summary ?: "Your plan awaits!"
}

fun exportToCsv(resolver: ContentResolver, uri: Uri, plan: WorkoutPlan, weights: Map<String, String>, rpes: Map<String, String>, checks: Map<String, Boolean>) {
    val header = "date,exercise,set,weight_kg,rpe,completed\n"
    val sb = StringBuilder(header)
    plan.days.forEach { day ->
        day.items.forEach { ex ->
            (1..ex.sets).forEach { setIdx ->
                val base = "${day.date}|${ex.name}|set$setIdx"
                val w = weights["${base}|w"] ?: ""
                val r = rpes["${base}|r"] ?: ""
                val done = if (checks[base] == true) "yes" else "no"
                sb.append("${day.date},${ex.name},$setIdx,$w,$r,$done\n")
            }
        }
    }
    resolver.openOutputStream(uri, "w").use { out: OutputStream? -> out?.write(sb.toString().toByteArray()) }
}
