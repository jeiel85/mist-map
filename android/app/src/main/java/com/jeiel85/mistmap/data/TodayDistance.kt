package com.jeiel85.mistmap.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.LocalDate
import java.time.ZoneId

/** 오늘 걸은 거리(m). 자정이 지나면 새 날짜로 다시 잰다. */
@OptIn(ExperimentalCoroutinesApi::class)
fun TrackRepository.todayDistance(zone: ZoneId = ZoneId.systemDefault()): Flow<Double> =
  flow {
    while (true) {
      val today = LocalDate.now(zone)
      val from = today.atStartOfDay(zone).toInstant().toEpochMilli()
      val until = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
      emit(from to until)
      delay((until - System.currentTimeMillis()).coerceAtLeast(1_000))
    }
  }.flatMapLatest { (from, until) -> observeDistance(from, until) }
