package reminder.syntax

import akka.http.scaladsl.model.DateTime

import scala.concurrent.duration.{Duration, HOURS, SECONDS}

object Extensions {

  def prettyTz(secOffset: Int): String = {
    val hoursOffs = Duration(secOffset, SECONDS).toHours
    val minsOffs  = Duration(secOffset - Duration(hoursOffs, HOURS).toSeconds, SECONDS).toMinutes
    s"UTC+$hoursOffs${if (minsOffs != 0) ":" + minsOffs else ""}"
  }

  implicit class DateTimeOps(time: DateTime) {

    def weekdayWithTimezone(timeOffset: Int): String =
      time
        .plus(Duration(timeOffset, SECONDS).toMillis)
        .toRfc1123DateTimeString()
        .substring(0, time.toRfc1123DateTimeString().length - 3) + prettyTz(timeOffset)

  }

}
