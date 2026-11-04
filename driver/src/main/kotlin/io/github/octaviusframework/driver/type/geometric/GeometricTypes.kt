package io.github.octaviusframework.driver.type.geometric

/** A `point`. */
data class PgPoint(val x: Double, val y: Double)

/** A `line`, `ax + by + c = 0`. */
data class PgLine(val a: Double, val b: Double, val c: Double)

/** An `lseg`: the line segment between [p1] and [p2]. */
data class PgLseg(val p1: PgPoint, val p2: PgPoint)

/** A `box` by its upper right ([high]) and lower left ([low]) corners. */
data class PgBox(val high: PgPoint, val low: PgPoint)

/** A `path`, open or [closed]. */
data class PgPath(val closed: Boolean, val points: List<PgPoint>)

/** A `polygon` by its vertices. */
data class PgPolygon(val points: List<PgPoint>)

/** A `circle`. */
data class PgCircle(val center: PgPoint, val radius: Double)
