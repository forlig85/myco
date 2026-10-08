package io.github.forlig85.memoauto.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppEntry(val label: String, val pkg: String)

/** 런처에 보이는 앱 목록에서 메모 앱을 고른다. */
@Composable
fun AppPickerDialog(onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메모 앱 선택") },
        text = {
            val list = apps
            if (list == null) Text("앱 목록을 불러오는 중…")
            else LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(list, key = { it.pkg }) { a ->
                    Text(
                        "${a.label}\n${a.pkg}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 10.dp)
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
