package reminder.notifier

import akka.http.scaladsl.model.DateTime
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser.decode

import scala.language.higherKinds

case class Event(topic: String, time: DateTime)

object Event {

  private case class RawEvent(topic: String, time: String)

  def fromJson(json: Json): Option[Event] =
    for {
      rawEvent <- decode[RawEvent](json.noSpaces).toOption
      time     <- DateTime.fromIsoDateTimeString(DateTime.now.year + "-" + rawEvent.time)
    } yield Event(rawEvent.topic, time)

}
