package com.hanwoo.stockreport

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.createChannel(this)
        ReportWorker.schedule(this)
        setContent { AppTheme { App() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainVM(app: Application) : AndroidViewModel(app) {
    var dates by mutableStateOf<List<String>>(emptyList())
    var report by mutableStateOf<Report?>(null)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var tab by mutableIntStateOf(0)

    private val ctx get() = getApplication<Application>()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                dates = Repo.index(ctx)
                val latest = dates.firstOrNull()
                if (latest != null) {
                    val cur = report?.date
                    report = Repo.report(ctx, if (cur != null && cur != latest && dates.contains(cur)) cur else latest, true)
                    Prefs.setSeen(ctx, latest)
                }
            } catch (e: Exception) {
                error = "불러오기 실패: ${e.message ?: e.javaClass.simpleName}"
            }
            loading = false
        }
    }

    fun open(date: String) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                report = Repo.report(ctx, date, date == dates.firstOrNull())
            } catch (e: Exception) {
                error = "불러오기 실패: ${e.message ?: e.javaClass.simpleName}"
            }
            loading = false
        }
    }
}

sealed interface Route {
    data object Home : Route
    data class Detail(val item: StockItem, val side: String) : Route
    data class Pdf(val path: String, val title: String) : Route
}

@Composable
fun App(vm: MainVM = viewModel()) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val nav: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { back() }
    NotificationPermission()

    when (val r = stack.last()) {
        Route.Home -> HomeScreen(vm, nav)
        is Route.Detail -> DetailScreen(r.item, r.side, back)
        is Route.Pdf -> PdfScreen(r.path, r.title, back)
    }
}

@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
