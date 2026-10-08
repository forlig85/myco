package io.github.forlig85.memoauto.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_HOME = 0
        const val TAB_SETTINGS = 1
        const val TAB_LOG = 2
    }

    /** onResume 마다 증가 → 권한 상태 등을 다시 읽게 한다. */
    private val resumeTick = mutableIntStateOf(0)
    private val tab = mutableIntStateOf(TAB_HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) tab.intValue = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
        else tab.intValue = savedInstanceState.getInt(EXTRA_TAB, TAB_HOME)
        setContent {
            AppTheme {
                Root(tab.intValue, { tab.intValue = it }, resumeTick.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) tab.intValue = intent.getIntExtra(EXTRA_TAB, TAB_HOME)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(EXTRA_TAB, tab.intValue)
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val accent = Color(0xFFE0A100)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = accent) else lightColorScheme(primary = Color(0xFF8A6200))
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(tab: Int, onTab: (Int) -> Unit, resumeTick: Int) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val titles = listOf("홈", "설정", "로그", "녹음 결과")
    Scaffold(
        topBar = {
            TabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
                titles.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = {
                        if (i == 3) {
                            // 최근 녹음 결과 화면 열기
                            val last = io.github.forlig85.memoauto.Prefs.lastRecording
                            if (last != null) ctx.startActivity(ResultActivity.intent(ctx, last, autoShare = false))
                            else io.github.forlig85.memoauto.Notifier.toast(ctx, "아직 저장된 녹음이 없습니다")
                        } else onTab(i)
                    }, text = { Text(t) })
                }
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                MainActivity.TAB_HOME -> HomeScreen(resumeTick, onOpenLog = { onTab(MainActivity.TAB_LOG) })
                MainActivity.TAB_SETTINGS -> SettingsScreen(resumeTick)
                else -> LogScreen()
            }
        }
    }
}
