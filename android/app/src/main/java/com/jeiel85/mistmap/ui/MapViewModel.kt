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
import android.os.PowerManager
import com.jeiel85.mistmap.data.GpxExporter
import com.jeiel85.mistmap.tracking.DayDiagnostics
import com.jeiel85.mistmap.tracking.Diagnostics
import com.jeiel85.mistmap.data.ImportSummary
import com.jeiel85.mistmap.importers.HistoryParser
import com.jeiel85.mistmap.importers.ImportException
import com.jeiel85.mistmap.importers.ImportPlanner
import com.jeiel85.mistmap.importers.ImportPlan
import com.jeiel85.mistmap.importers.ParseSummary
import com.jeiel85.mistmap.data.UndoneImport
import com.jeiel85.mistmap.reveal.CellGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import java.io.File
import kotlin.coroutines.coroutineContext

class MapViewModel(app: Application) : AndroidViewModel(app) {
  private val repo = (app as MistApp).repository

  val status: StateFlow<TrackingState.Status> = TrackingState.status
  val todayM: StateFlow<Double> = repo.todayDistance()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

  private val _notice = MutableStateFlow<Notice?>(null)
  val notice = _notice.asStateFlow()

  /** 안내를 눌러 기록 모음 시트를 열어 달라는 요청 */
  val openRecordsRequest = MutableStateFlow(false)

  private val _rendererError = MutableStateFlow<String?>(null)
  val rendererError = _rendererError.asStateFlow()

  private var bridgeJob: Job? = null
  private var insets = 0 to 0
  private var bridge: RendererBridge? = null
  private var pendingFit: ((RendererBridge) -> Unit)? = null

  // init의 코루틴은 시작이 끝나 있으면 바로 실행되므로, 거기서 쓰는 속성은 init보다 위에 둔다
  private val settings by lazy {
    getApplication<Application>().getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
  }

  /** 기록 중 이만큼(초) 손대지 않으면 간단히 보기로. 0이면 저절로 들어가지 않는다 */
  val simpleAfterSec = MutableStateFlow(60)

  init {
    viewModelScope.launch {
      (app as MistApp).startup.await()
      simpleAfterSec.value = settings.getInt(KEY_SIMPLE_AFTER, 60)
      introduceOnce()
      if (TrackingState.interrupted.value && status.value == TrackingState.Status.OFF) {
        TrackingState.interrupted.value = false
        show(
          Notice(
            "지난 기록이 중간에 끊겼어요. 휴대폰 절전이 앱을 닫았을 수 있어요.",
            "기록 모음에서 확인",
          ) { openRecordsRequest.value = true },
        )
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

  /** 원본 파일 이름은 자동 백업에 들어가는 mist.db 대신 기기 안(SharedPreferences, 백업 제외)에만 둔다 */
  private val importNames by lazy {
    getApplication<Application>().getSharedPreferences("import_names", android.content.Context.MODE_PRIVATE)
  }
  private val namesChanged = MutableStateFlow(0)

  val imports: StateFlow<List<ImportSummary>> = repo.observeImports()
    .combine(namesChanged) { list, _ ->
      list.map { item -> importNames.getString(item.id.toString(), null)?.let { item.copy(label = "${item.label} · $it") } ?: item }
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  private val _busy = MutableStateFlow<String?>(null)
  /** 가져오기·내보내기 진행 중 안내. null이면 쉬는 중 */
  val busy = _busy.asStateFlow()

  private var importJob: Job? = null

  /** 가져오는 중이면 취소할 수 있다 */
  val cancellable = MutableStateFlow(false)

  fun cancelImport() {
    importJob?.cancel()
  }

  /**
   * 파일 선택이나 다른 앱의 공유로 받은 기록 파일을 읽어 그곳의 안개를 걷는다.
   * 1) 받은 파일을 앱 캐시로 먼저 복사한다: 공유로 받은 주소는 읽기 권한이 오래 가지 않을 수 있다
   * 2) 복사본을 요소 하나씩 읽으며 바로 칸으로 바꾼다(큰 파일도 메모리에 통째로 두지 않음), 진행률은 읽은 바이트로
   */
  fun importFrom(uri: Uri) {
    if (_busy.value != null) {
      show(Notice("앞의 작업이 끝난 뒤에 다시 해 주세요."))
      return
    }
    importJob = viewModelScope.launch {
      val name = displayName(uri) ?: "기록 파일"
      val copy = File(getApplication<Application>().cacheDir, "import-${System.nanoTime()}")
      _busy.value = "$name 받는 중…"
      cancellable.value = true
      try {
        val (summary, plan, added, originId) = withContext(Dispatchers.IO) {
          copyToCache(uri, copy, onProgress = { _busy.value = "$name 받는 중… $it%" })
          val total = copy.length().coerceAtLeast(1)
          val planner = ImportPlanner()
          val summary = CountingInputStream(copy.inputStream()) { read ->
            _busy.value = "$name 읽는 중… ${(read * 100 / total).toInt()}%"
          }.use { input ->
            HistoryParser.stream(input, planner, checkCancelled = { ensureActive() }, maxXmlChars = xmlLimitChars())
          }
          ensureActive()
          val plan = planner.build()
          _busy.value = "$name 저장하는 중…"
          val (originId, added) = repo.importCells(summary.format, plan.cellIds)
          Imported(summary, plan, added, originId)
        }
        importNames.edit().putString(originId.toString(), name).apply()
        namesChanged.value++
        val fit = { b: RendererBridge -> b.sendFit(plan.south, plan.west, plan.north, plan.east) }
        // 공유로 앱이 막 열렸다면 렌더러가 아직 없으니 연결될 때 맞춘다
        bridge?.let(fit) ?: run { pendingFit = fit }
        val vehicle = if (summary.vehicleSkipped > 0) " · 차량 이동 ${summary.vehicleSkipped}구간은 뺐어요" else ""
        show(
          Notice(
            if (added == 0) "${summary.format} 기록의 장소는 이미 모두 밝혀져 있어요$vehicle"
            else "${summary.format}에서 ${"%,d".format(added)}칸을 새로 밝혔어요 · ${"%.1f".format(plan.distanceM / 1000)}km$vehicle",
          ),
        )
      } catch (e: CancellationException) {
        show(Notice("$name 가져오기를 취소했어요."))
      } catch (e: ImportException) {
        show(Notice("$name: ${e.message}"))
      } catch (e: OutOfMemoryError) {
        // 칸 목록이 아주 크면 그래도 힙을 넘길 수 있다. 앱을 죽이지 않고 나눠 넣으라고 안내한다
        Log.e(MistApp.TAG, "가져오기 메모리 부족: $uri", e)
        show(Notice("$name: 이 휴대폰에서 한 번에 읽기엔 너무 커요. 기간을 나눠 내보낸 파일을 넣어 주세요"))
      } catch (e: Exception) {
        Log.e(MistApp.TAG, "가져오기 실패: $uri", e)
        show(Notice("$name 을(를) 읽다가 문제가 생겼어요."))
      } finally {
        copy.delete()
        cancellable.value = false
        _busy.value = null
      }
    }
  }

  private data class Imported(val summary: ParseSummary, val plan: ImportPlan, val added: Int, val originId: Long)

  private suspend fun copyToCache(uri: Uri, target: File, onProgress: (Int) -> Unit) {
    val size = fileSize(uri)
    if (size != null && size > MAX_IMPORT_BYTES) {
      throw ImportException("파일이 너무 커요(${MAX_IMPORT_BYTES / 1_000_000}MB까지). 기간을 나눠 내보낸 파일을 넣어 주세요")
    }
    val input = getApplication<Application>().contentResolver.openInputStream(uri)
      ?: throw ImportException("파일을 열 수 없어요")
    input.use { src ->
      target.outputStream().use { dst ->
        val buf = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
          coroutineContext.ensureActive()
          val n = src.read(buf)
          if (n < 0) break
          dst.write(buf, 0, n)
          copied += n
          if (copied > MAX_IMPORT_BYTES) {
            throw ImportException("파일이 너무 커요(${MAX_IMPORT_BYTES / 1_000_000}MB까지). 기간을 나눠 내보낸 파일을 넣어 주세요")
          }
          if (size != null) onProgress((copied * 100 / size).toInt())
        }
      }
    }
  }

  /** GPX·KML은 정규식으로 읽어 문자열로 올리므로 힙에 맞춰 막는다(JSON은 스트리밍이라 해당 없음) */
  private fun xmlLimitChars(): Long = minOf(MAX_XML_CHARS, Runtime.getRuntime().maxMemory() / 16)

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

  /** 되돌린 뒤 "되살리기"를 누르면 같은 칸을 같은 이름으로 다시 넣는다 */
  fun undoImport(summary: ImportSummary) {
    viewModelScope.launch {
      val name = importNames.getString(summary.id.toString(), null)
      val undone = repo.undoImport(summary.id)
      importNames.edit().remove(summary.id.toString()).apply()
      resync()
      show(
        Notice(
          "${summary.label} 기록을 되돌렸어요 · ${"%,d".format(undone.removed)}칸이 다시 안개에 덮였어요",
          "되살리기",
        ) { restoreImport(undone, name) },
      )
    }
  }

  private fun restoreImport(undone: UndoneImport, name: String?) {
    viewModelScope.launch {
      val (originId, added) = repo.importCells(undone.label, undone.cellIds)
      name?.let { importNames.edit().putString(originId.toString(), it).apply() }
      namesChanged.value++
      show(Notice("되살렸어요 · ${"%,d".format(added)}칸"))
    }
  }

  /**
   * 걸은 길(최근 30일 원시 위치, D4)은 트랙으로, 가져온 기록의 칸은 그 중심을 웨이포인트로 쓴다.
   * 내보낸 파일을 다른 휴대폰의 Mist Map에 넣으면 같은 곳이 밝혀진다.
   */
  fun exportGpx(uri: Uri) {
    viewModelScope.launch {
      _busy.value = "GPX 만드는 중…"
      try {
        val (fixCount, placeCount) = withContext(Dispatchers.IO) {
          val fixes = repo.allFixes()
          val places = repo.importedCellIds().map(CellGrid::center)
          val resolver = getApplication<Application>().contentResolver
          resolver.openOutputStream(uri)?.bufferedWriter()?.use { GpxExporter.write(fixes, it, places) }
            ?: throw java.io.IOException("파일을 쓸 수 없음: $uri")
          fixes.size to places.size
        }
        show(
          Notice(
            if (fixCount + placeCount == 0) "내보낼 기록이 없어요. 기록을 켜고 걸어 보세요"
            else "걸은 길 ${"%,d".format(fixCount)}개 지점(최근 30일)과 가져온 곳 ${"%,d".format(placeCount)}칸을 GPX로 저장했어요",
          ),
        )
      } catch (e: Exception) {
        Log.e(MistApp.TAG, "GPX 내보내기 실패", e)
        show(Notice("GPX 파일을 저장하지 못했어요."))
      } finally {
        _busy.value = null
      }
    }
  }

  // ---- 설정 ----

  fun setSimpleAfter(sec: Int) {
    simpleAfterSec.value = sec
    settings.edit().putInt(KEY_SIMPLE_AFTER, sec).apply()
  }

  /** 처음 연 사람에게 한 번만: 무엇을 하면 되는지 */
  private fun introduceOnce() {
    if (settings.getBoolean(KEY_INTRO_SHOWN, false)) return
    settings.edit().putBoolean(KEY_INTRO_SHOWN, true).apply()
    show(Notice("'기록 켜기'를 누르고 걸으면 반디가 밤안개를 밝혀요. 다른 앱 기록은 오른쪽 위 '기록 모음'에서 넣을 수 있어요."))
  }

  // ---- 하루 종일 기록 (M3) ----

  private val diagnosticsStore by lazy { Diagnostics(getApplication()) }

  /** 오늘 기록 상태 (기록 모음 시트를 열 때 읽는다) */
  fun diagnostics(): DayDiagnostics = diagnosticsStore.today()

  /** 휴대폰 절전(배터리 최적화)이 이 앱을 제한하는지. 제한되면 기록이 끊길 수 있다 */
  fun batteryRestricted(): Boolean {
    val power = getApplication<Application>().getSystemService(PowerManager::class.java)
    return !power.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
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
private const val MAX_XML_CHARS = 32_000_000L

/** 캐시로 복사해 스트리밍으로 읽으므로 JSON은 이만큼까지 받는다(몇 년 치 Records.json) */
private const val MAX_IMPORT_BYTES = 1_000_000_000L

/** 읽은 바이트 수를 알려 주는 입력 (진행률) */
private class CountingInputStream(input: java.io.InputStream, private val onRead: (Long) -> Unit) :
  java.io.FilterInputStream(input) {
  private var count = 0L
  private var reported = 0L

  override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

  override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

  private fun advance(n: Long) {
    count += n
    if (count - reported >= 256 * 1024) {
      reported = count
      onRead(count)
    }
  }
}

private const val KEY_SIMPLE_AFTER = "simpleAfterSec"
private const val KEY_INTRO_SHOWN = "introShown"

/** 화면 위쪽에 잠깐 뜨는 안내. [action]이 있으면 눌렀을 때 실행한다. */
data class Notice(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)
