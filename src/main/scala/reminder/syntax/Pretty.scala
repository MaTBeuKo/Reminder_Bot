package reminder.syntax

import akka.http.scaladsl.model.DateTime
import canoe.models.{Chat, PrivateChat}
import cats.effect.IO

import scala.concurrent.duration.{Duration, HOURS, SECONDS}

object Pretty {

  def prettyTz(secOffset: Int): String = {
    val hoursOffs = Duration(secOffset, SECONDS).toHours
    val minsOffs  = Duration(secOffset - Duration(hoursOffs, HOURS).toSeconds, SECONDS).toMinutes
    s"UTC+$hoursOffs${if (minsOffs != 0) ":" + minsOffs else ""}"
  }

  def prettyUser(chat: Chat): String =
    chat match {
      case PrivateChat(_, username, firstName, lastName) =>
        firstName.getOrElse("Anon") + " \'" +
          username.getOrElse("Anon") + "\' " +
          lastName.getOrElse("Anonov")
      case _ => "Not human user"
    }

  implicit class DateTimeOps(time: DateTime) {

    def weekdayWithTimezone(timeOffset: Int): String =
      time
        .plus(Duration(timeOffset, SECONDS).toMillis)
        .toRfc1123DateTimeString()
        .substring(0, time.toRfc1123DateTimeString().length - 3) + prettyTz(timeOffset)

  }

}
