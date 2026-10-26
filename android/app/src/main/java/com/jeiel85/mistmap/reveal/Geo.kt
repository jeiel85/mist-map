package com.jeiel85.mistmap.reveal

import kotlin.math.cos
import kotlin.math.hypot

data class LatLng(val lat: Double, val lng: Double)

/** 짧은 거리용 평면 근사. 웹 프로토타입(`distM`)과 같은 식이라 두 쪽의 표본 위치가 맞는다. */
object Geo {
  private const val M_PER_DEG_LAT = 110_540.0

  private fun mPerDegLng(lat: Double) = 111_320.0 * cos(Math.toRadians(lat))

  fun distanceM(a: LatLng, b: LatLng): Double {
    val dx = (b.lng - a.lng) * mPerDegLng((a.lat + b.lat) / 2)
    val dy = (b.lat - a.lat) * M_PER_DEG_LAT
    return hypot(dx, dy)
  }

  fun lerp(a: LatLng, b: LatLng, t: Double) =
    LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
}
