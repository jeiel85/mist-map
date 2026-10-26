package com.jeiel85.mistmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jeiel85.mistmap.data.ImportSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("yyyy.M.d HH:mm")

/** 기록 모음: 다른 앱 기록 가져오기, GPX 내보내기, 가져온 파일 되돌리기. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsSheet(
  imports: List<ImportSummary>,
  busy: String?,
  onImport: () -> Unit,
  onExport: () -> Unit,
  onUndo: (ImportSummary) -> Unit,
  onDismiss: () -> Unit,
) {
  var confirm by remember { mutableStateOf<ImportSummary?>(null) }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = Mist.Night,
    contentColor = Mist.Moon,
    scrimColor = Color(0x99050818),
  ) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
      Text("기록 모음", style = TextStyle(fontFamily = Mist.Hand, fontSize = 30.sp, color = Mist.Firefly))
      Text(
        "다른 앱에 쌓아 둔 발자국으로 밤안개를 한꺼번에 걷어 내요.",
        fontSize = 13.sp,
        color = Mist.MoonSoft,
        modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetButton("파일 가져오기", primary = true, enabled = busy == null, onClick = onImport, modifier = Modifier.weight(1f))
        SheetButton("GPX 내보내기", primary = false, enabled = busy == null, onClick = onExport, modifier = Modifier.weight(1f))
      }
      Text(
        "GPX · KML · GeoJSON · 구글 타임라인 JSON",
        fontSize = 11.sp,
        color = Mist.MoonSoft,
        modifier = Modifier.padding(top = 8.dp),
      )
      busy?.let { Text(it, fontSize = 13.sp, color = Mist.Firefly, modifier = Modifier.padding(top = 10.dp)) }

      Spacer(Modifier.height(18.dp))
      Text("가져온 기록", style = TextStyle(fontFamily = Mist.Hand, fontSize = 20.sp, color = Mist.Moon))
      if (imports.isEmpty()) {
        Text(
          "아직 가져온 기록이 없어요. 구글 지도 타임라인이나 운동 앱의 GPX를 넣어 보세요.",
          fontSize = 13.sp,
          color = Mist.MoonSoft,
          modifier = Modifier.padding(vertical = 10.dp),
        )
      } else {
        LazyColumn(Modifier.heightIn(max = 280.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(imports, key = { it.id }) { item -> ImportRow(item, onUndo = { confirm = item }) }
        }
      }
      Text(
        "파일은 이 휴대폰 안에서만 읽고 어디에도 올리지 않아요. 내보내기에는 최근 30일 동안 걸은 길이 담겨요.",
        fontSize = 11.sp,
        color = Mist.MoonSoft,
        modifier = Modifier.padding(top = 14.dp),
      )
    }
  }

  confirm?.let { item ->
    AlertDialog(
      onDismissRequest = { confirm = null },
      containerColor = Mist.Night,
      title = { Text("이 기록을 되돌릴까요?", color = Mist.Moon) },
      text = {
        Text(
          "${item.label}에서 밝힌 곳이 다시 안개에 덮여요. 걸어서 밝힌 곳과 다른 파일이 함께 밝힌 곳은 그대로 남아요.",
          color = Mist.MoonSoft,
        )
      },
      confirmButton = {
        TextButton(onClick = { onUndo(item); confirm = null }) { Text("되돌리기", color = Mist.Pink) }
      },
      dismissButton = { TextButton(onClick = { confirm = null }) { Text("그대로 두기", color = Mist.Moon) } },
    )
  }
}

@Composable
private fun ImportRow(item: ImportSummary, onUndo: () -> Unit) {
  GlowBox(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(item.label, fontSize = 14.sp, color = Mist.Moon, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
          "${dateFormat.format(Instant.ofEpochMilli(item.createdAt).atZone(ZoneId.systemDefault()))} · ${"%,d".format(item.cells)}칸",
          fontSize = 12.sp,
          color = Mist.MoonSoft,
        )
      }
      TextButton(onClick = onUndo) { Text("되돌리기", color = Mist.Pink, fontSize = 13.sp) }
    }
  }
}

@Composable
private fun SheetButton(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val shape = RoundedCornerShape(999.dp)
  GlowBox(
    modifier,
    shape = shape,
    fill = if (primary) Mist.Firefly else Mist.Card,
    border = if (primary) Color.Transparent else Mist.Line,
  ) {
    Box(
      Modifier
        .fillMaxWidth()
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .background(if (enabled) Color.Transparent else Color(0x66050818), shape)
        .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        label,
        textAlign = TextAlign.Center,
        style = TextStyle(fontFamily = Mist.Hand, fontSize = 20.sp, color = if (primary) Mist.OnFirefly else Mist.Moon),
      )
    }
  }
}
