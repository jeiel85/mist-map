package com.jeiel85.mistmap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.jeiel85.mistmap.MistApp
import com.jeiel85.mistmap.data.todayDistance
import com.jeiel85.mistmap.renderer.RendererBridge
import com.jeiel85.mistmap.tracking.Fix
import com.jeiel85.mistmap.tracking.TrackingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.OpenableColumns
import com.jeiel85.mistmap.data.GpxExporter
import com.jeiel85.mistmap.data.ImportSummary
import com.jeiel85.mistmap.importers.HistoryParser
import com.jeiel85.mistmap.importers.ImportException
import com.jeiel85.mistmap.importers.ImportPlanner

class MapViewModel(app: Application) : AndroidViewModel(app) {
  private val repo = (app as MistApp).repository

  val status: StateFlow<TrackingState.Status> = TrackingState.status
  val todayM: StateFlow<Double> = repo.todayDistance()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

  private val _notice = MutableStateFlow<Notice?>(null)
  val notice = _notice.asStateFlow()

  private val _rendererError = MutableStateFlow<String?>(null)
  val rendererError = _rendererError.asStateFlow()

  private var bridgeJob: Job? = null
  private var insets = 0 to 0
  private var bridge: RendererBridge? = null
  private var pendingFit: ((RendererBridge) -> Unit)? = null

  init {
    viewModelScope.launch {
      (app as MistApp).startup.await()
      if (TrackingState.interrupted.value && status.value == TrackingState.Status.OFF) {
        TrackingState.interrupted.value = false
        show(Notice("지난 기록이 중간에 끊겼어요. 휴대폰 절전 설정이 앱을 닫았을 수 있어요."))
      }
    }
  }

  fun show(notice: Notice) {
    _notice.value = notice
  }

  fun dismissNotice(notice: Notice) {
    if (_notice.value == notice) _notice.value = null
  }

  /** 렌더러가 (다시) 연결되면 칸 전체와 위치를 보내고, 이후 새로 밝힌 칸과 이동을 이어서 보낸다. */
  fun attach(next: RendererBridge?) {
    bridgeJob?.cancel()
    bridge?.close()
    bridge = next
    if (next == null) return
    _rendererError.value = null
    sync(next)
  }

  /** 칸이 사라졌을 때(가져오기 되돌리기) 렌더러에 칸 전체를 다시 보낸다. */
  private fun resync() {
    val current = bridge ?: return
    bridgeJob?.cancel()
    sync(current)
  }

  private fun sync(next: RendererBridge) {
    bridgeJob = viewModelScope.launch {
      (getApplication<MistApp>()).startup.await()
      next.sendInsets(insets.first, insets.second)
      initialPlayer()?.let(next::sendPlayer)

      val snapshot = repo.cellSnapshot()
      val sent = HashSet<Long>(snapshot.ids)
      next.sendCells(RendererBridge.CELLS_SET, snapshot.ids)
      pendingFit?.let { it(next); pendingFit = null }
      var after = snapshot.latestFirstAt
      launch {
        repo.observeLatestFirstAt().filterNotNull().collect { latest ->
          if (latest <= after) return@collect
          val fresh = repo.cellsFirstSeenAfter(after).filter(sent::add)
          after = latest
          if (fresh.isNotEmpty()) next.sendCells(RendererBridge.CELLS_ADD, fresh)
        }
      }
      launch { TrackingState.lastFix.filterNotNull().collect(next::sendPlayer) }
    }
  }

  // ---- 가져오기 · 되돌리기 · 내보내기 (M2) ----

  val imports: StateFlow<List<ImportSummary>> = repo.observeImports()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _busy = MutableStateFlow<String?>(null)
  /** 가져오기·내보내기 진행 중 안내. null이면 쉬는 중 */
  val busy = _busy.asStateFlow()

  /** 파일 선택이나 다른 앱의 공유로 받은 기록 파일을 읽어 그곳의 안개를 걷는다. */
  fun importFrom(uri: Uri) {
    if (_busy.value != null) {
      show(Notice("앞의 작업이 끝난 뒤에 다시 해 주세요."))
      return
    }
    viewModelScope.launch {
      val name = displayName(uri) ?: "기록 파일"
      _busy.value = "$name 읽는 중…"
      try {
        val result = withContext(Dispatchers.Default) {
          val text = readText(uri) ?: throw ImportException("파일을 열 수 없어요")
          val history = HistoryParser.parse(text)
          val plan = ImportPlanner.plan(history)
          val added = repo.importCells("${history.format} · $name", plan.cellIds)
          Triple(history, plan, added)
        }
        val (history, plan, added) = result
        val fit = { b: RendererBridge -> b.sendFit(plan.south, plan.west, plan.north, plan.east) }
        // 공유로 앱이 막 열렸다면 렌더러가 아직 없으니 연결될 때 맞춘다
        bridge?.let(fit) ?: run { pendingFit = fit }
        show(
          Notice(
            if (added == 0) "${history.format} 기록의 장소는 이미 모두 밝혀져 있어요"
            else "${history.format}에서 ${"%,d".format(added)}칸을 새로 밝혔어요 · ${"%.1f".format(plan.distanceM / 1000)}km",
          ),
        )
      } catch (e: ImportException) {
        show(Notice("$name: ${e.message}"))
      } catch (e: OutOfMemoryError) {
        // 상한 안쪽이어도 JSON 트리가 힙을 넘길 수 있다. 앱을 죽이지 않고 나눠 넣으라고 안내한다
        Log.e(MistApp.TAG, "가져오기 메모리 부족: $uri", e)
        show(Notice("$name: 이 휴대폰에서 한 번에 읽기엔 너무 커요. 기간을 나눠 내보낸 파일을 넣어 주세요"))
      } catch (e: Exception) {
        Log.e(MistApp.TAG, "가져오기 실패: $uri", e)
        show(Notice("$name 을(를) 읽다가 문제가 생겼어요."))
      } finally {
        _busy.value = null
      }
    }
  }

  /**
   * 너무 큰 파일은 메모리를 넘기므로 미리 막는다. 몇 년 치 Records.json은 나눠 넣어야 한다.
   * 파일 전체가 문자열 → JSON 트리 → 경로로 불어나므로(대략 파일 크기의 10배 이상) 상한은 힙의 1/16로 잡는다.
   * 크기를 알 수 있으면 읽기 전에 막고, 모르면 문자 단위로 읽다가 상한을 넘는 순간 멈춘다(바이트 버퍼를 두 벌 두지 않는다).
   */
  private fun readText(uri: Uri): String? {
    val limit = importLimitChars()
    val tooBig = ImportException("파일이 너무 커요(${limit / 1_000_000}MB까지). 기간을 나눠 내보낸 파일을 넣어 주세요")
    fileSize(uri)?.let { if (it > limit) throw tooBig }
    val resolver = getApplication<Application>().contentResolver
    resolver.openInputStream(uri)?.reader(Charsets.UTF_8)?.use { reader ->
      val text = StringBuilder()
      val buf = CharArray(64 * 1024)
      while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        text.append(buf, 0, n)
        if (text.length > limit) throw tooBig
      }
      return text.toString()
    }
    return null
  }

  private fun importLimitChars(): Long = minOf(MAX_IMPORT_CHARS, Runtime.getRuntime().maxMemory() / 16)

  private fun fileSize(uri: Uri): Long? = try {
    getApplication<Application>().contentResolver
      .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
      ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
  } catch (e: Exception) {
    Log.w(MistApp.TAG, "파일 크기를 읽지 못함: $uri", e)
    null
  }

  private fun displayName(uri: Uri): String? = try {
    getApplication<Application>().contentResolver
      .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
  } catch (e: Exception) {
    Log.w(MistApp.TAG, "파일 이름을 읽지 못함: $uri", e)
    null
  } ?: uri.lastPathSegment

  fun undoImport(summary: ImportSummary) {
    viewModelScope.launch {
      val removed = repo.undoImport(summary.id)
      resync()
      show(Notice("${summary.label} 기록을 되돌렸어요 · ${"%,d".format(removed)}칸이 다시 안개에 덮였어요"))
    }
  }

  /** 최근 30일의 원시 위치를 GPX로 쓴다 (D4: 그보다 오래된 원시 위치는 남아 있지 않다). */
  fun exportGpx(uri: Uri) {
    viewModelScope.launch {
      _busy.value = "GPX 만드는 중…"
      try {
        val count = withContext(Dispatchers.IO) {
          val fixes = repo.allFixes()
          val resolver = getApplication<Application>().contentResolver
          resolver.openOutputStream(uri)?.bufferedWriter()?.use { GpxExporter.write(fixes, it) }
            ?: throw java.io.IOException("파일을 쓸 수 없음: $uri")
          fixes.size
        }
        show(Notice(if (count == 0) "내보낼 기록이 없어요. 기록을 켜고 걸어 보세요" else "최근 30일 기록 ${"%,d".format(count)}개 지점을 GPX로 저장했어요"))
      } catch (e: Exception) {
        Log.e(MistApp.TAG, "GPX 내보내기 실패", e)
        show(Notice("GPX 파일을 저장하지 못했어요."))
      } finally {
        _busy.value = null
      }
    }
  }

  fun updateInsets(topDp: Int, bottomDp: Int) {
    if (insets == topDp to bottomDp) return
    insets = topDp to bottomDp
    bridge?.sendInsets(topDp, bottomDp)
  }

  fun onRendererMessage(type: String, message: String?) {
    when (type) {
      "ready" -> Log.i(MistApp.TAG, "렌더러 준비 완료")
      "error" -> {
        Log.e(MistApp.TAG, "렌더러 오류: $message")
        _rendererError.value = message ?: "알 수 없는 오류"
      }
      else -> Log.w(MistApp.TAG, "알 수 없는 렌더러 메시지: $type")
    }
  }

  /** 캐릭터를 처음 둘 곳: 기록 중 위치 → 저장된 마지막 위치 → 기기의 마지막 위치. */
  @SuppressLint("MissingPermission") // 권한을 확인한 뒤에만 부른다
  private suspend fun initialPlayer(): Fix? {
    TrackingState.lastFix.value?.let { return it }
    repo.latestFix()?.let { return it }
    val context = getApplication<Application>()
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    return try {
      LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
        ?.let { Fix(it.time, it.latitude, it.longitude, it.accuracy, null) }
    } catch (e: Exception) {
      Log.w(MistApp.TAG, "마지막 위치를 가져오지 못함", e)
      null
    }
  }

  override fun onCleared() {
    bridge?.close()
  }
}

/** 힙이 넉넉한 기기에서도 이보다 큰 파일은 받지 않는다 (글자 수 ≈ 바이트) */
private const val MAX_IMPORT_CHARS = 32_000_000L

/** 화면 위쪽에 잠깐 뜨는 안내. [action]이 있으면 눌렀을 때 실행한다. */
data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)
