package com.jeiel85.mistmap.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jeiel85.mistmap.tracking.Fix
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Room 쿼리를 실제 SQLite(메모리)로 시험한다. 되돌리기가 걸어서 밝힌 칸을 지우는 회귀를 막는다 (이슈 #6). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackRepositoryTest {
  private lateinit var mist: MistDatabase
  private lateinit var fixes: FixDatabase
  private lateinit var repo: TrackRepository

  @Before fun open() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    mist = Room.inMemoryDatabaseBuilder(context, MistDatabase::class.java).allowMainThreadQueries().build()
    fixes = Room.inMemoryDatabaseBuilder(context, FixDatabase::class.java).allowMainThreadQueries().build()
    repo = TrackRepository(mist, fixes)
  }

  @After fun close() {
    mist.close()
    fixes.close()
  }

  private fun fix(time: Long) = Fix(time, 37.5446, 127.0560, 5f, null)

  @Test fun undoRemovesOnlyCellsThatNoOtherOriginLit() = runTest {
    val session = repo.startSession(1_000)
    repo.record(session, fix(1_000), 0.0, listOf(1L, 2L)) // 걸어서 밝힌 칸
    val (a, addedA) = repo.importCells("GPX", listOf(2L, 3L, 4L))
    val (_, addedB) = repo.importCells("KML", listOf(4L, 5L))
    assertEquals(2, addedA) // 2는 이미 걸어서 밝힘
    assertEquals(1, addedB)

    val undone = repo.undoImport(a)
    // 3만 사라진다: 2는 걸어서, 4는 KML이 함께 밝혔다
    assertEquals(1, undone.removed)
    assertEquals(listOf(2L, 3L, 4L), undone.cellIds.sorted())
    assertEquals("GPX", undone.label)
    assertEquals(setOf(1L, 2L, 4L, 5L), repo.cellSnapshot().ids.toSet())

    // 되살리기: 같은 칸을 다시 넣으면 3이 돌아온다
    val (_, restored) = repo.importCells(undone.label, undone.cellIds)
    assertEquals(1, restored)
    assertEquals(setOf(1L, 2L, 3L, 4L, 5L), repo.cellSnapshot().ids.toSet())
  }

  @Test fun importListCountsCellsAndLeavesFileNamesOut() = runTest {
    repo.importCells("구글 타임라인", listOf(10L, 11L, 12L), at = 5_000)
    repo.importCells("GPX", listOf(12L), at = 6_000)
    val list = repo.observeImports().first()
    assertEquals(listOf("GPX" to 1, "구글 타임라인" to 3), list.map { it.label to it.cells })
    assertEquals(setOf(10L, 11L, 12L), repo.importedCellIds().toSet())
  }

  @Test fun firstAtKeepsIncreasingEvenWhenFixTimesGoBack() = runTest {
    val session = repo.startSession(0)
    repo.record(session, fix(9_000_000_000_000), 0.0, listOf(1L)) // 위치 시각이 기기 시계보다 한참 앞섬
    val after = repo.cellSnapshot().latestFirstAt
    repo.record(session, fix(1_000), 0.0, listOf(2L)) // 시각이 뒤로 감
    // 화면은 "after보다 큰 firstAt"을 새 칸으로 받는다. 위치 시각과 상관없이 새 칸이 잡혀야 한다
    assertEquals(listOf(2L), repo.cellsFirstSeenAfter(after))
  }

  @Test fun recordAddsDistanceAndClosesDanglingSessions() = runTest {
    val session = repo.startSession(1_000)
    repo.record(session, fix(2_000), 120.0, listOf(1L))
    repo.record(session, fix(3_000), 80.0, listOf(1L))
    assertEquals(200.0, repo.observeDistance(0, 10_000).first(), 0.001)
    // 끝나지 않은 세션은 마지막 위치 시각으로 닫는다
    assertEquals(1, repo.closeDanglingSessions())
    assertEquals(0, repo.closeDanglingSessions())
    assertTrue(repo.latestFix()!!.time == 3_000L)
  }
}
