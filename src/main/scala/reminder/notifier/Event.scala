package reminder.notifier

import io.circe._
import io.circe.parser._
import akka.http.scaladsl.model.DateTime
import cats.effect.IO
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser.decode
import reminder.exception.ParseException

case class Event(topic: String, time: DateTime)

object Event {

  def fromJson(json: Json): IO[Event] = {
    case class RawEvent(topic: String, time: String)
    for {
      rawEvent <- IO.fromEither(decode[RawEvent](json.noSpaces))
      event <- DateTime.fromIsoDateTimeString(DateTime.now.year + "-" + rawEvent.time) match {
        case Some(time) => IO.pure(Event(rawEvent.topic, time))
        case None       => IO.raiseError(new ParseException("Couldn't parse DateTime from json"))
      }
    } yield event
  }

}
