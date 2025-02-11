package reminder.notifier

import akka.http.scaladsl.model.DateTime
import cats.effect.kernel.Sync
import cats.implicits._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser.decode
import reminder.exception.ParseException

import scala.language.higherKinds

case class Event(topic: String, time: DateTime)

object Event {

  def fromJson[F[_]](json: Json)(implicit
    M: Sync[F]
  ): F[Event] = {
    case class RawEvent(topic: String, time: String)
    for {
      rawEvent <- M.fromEither(decode[RawEvent](json.noSpaces))
      event <- DateTime.fromIsoDateTimeString(DateTime.now.year + "-" + rawEvent.time) match {
        case Some(time) => M.pure(Event(rawEvent.topic, time))
        case None       => M.raiseError(new ParseException("Couldn't parse DateTime from json"))
      }
    } yield event
  }

}
