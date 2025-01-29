package reminder.bot

import akka.http.scaladsl.model.DateTime
import canoe.api.{TelegramClient, _}
import canoe.models.messages.{LocationMessage, TelegramMessage, TextMessage}
import canoe.syntax._
import cats.effect.IO
import io.circe.parser
import reminder.api.{GptManager, TimezoneManager}
import reminder.dao.{DBEvent, DataBase}
import reminder.notifier.Event
import reminder.syntax.Extensions
import sttp.client3.SttpBackend

import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}

class ResponseService(
  manager: GptManager,
  config: BotConfig,
  db: DataBase,
  backend: SttpBackend[IO, Any]
) {

  private val timezoneManager = new TimezoneManager(config.bdcKey, backend)

  private def makePrompt(text: String, tzOffset: Int): String = {
    import reminder.syntax.Extensions._
    val tz        = prettyTz(tzOffset)
    val localDate = DateTime.now.weekdayWithTimezone(tzOffset)
    "Extract topic, date and time of the event described in the end. " +
      s"Convert date and time to $tz. Don't indicate year. Current time is: " +
      localDate + ". " +
      s"Dont add anything else, your reply must be exactly in this format. Convert resulting time to $tz." +
      "{\"topic\":\"topic name\",\"time\":\"mm-ddThh:mm:ss\"}\nEvent:\n" + text
  }

  private def parseGptResponse(text: String): IO[Event] =
    for {
      json  <- IO.fromEither(parser.parse(text))
      event <- Event.fromJson(json)
    } yield event

  private def tryToReplyN(txt: String, count: Int): IO[Option[Event]] = {
    import retry._
    retryingOnFailures[Option[Event]](
      RetryPolicies.limitRetries[IO](count),
      opt => IO(opt.nonEmpty),
      (_, y) => if (y.givingUp) IO.println("too many retries, giving up") else IO()
    )(tryToReply(txt))
  }

  private def tryToReply(txt: String): IO[Option[Event]] = {
    for {
      gptResponseStrOpt <- manager
        .ask(txt, config.gptCount, parseGptResponse(_).option.map(_.nonEmpty))
        .handleErrorWith(er => IO.println("ask throw exception: " + er) *> IO(None))
      event = for {
        gptResponseStr <- IO.fromOption(gptResponseStrOpt)(
          new Exception("gpt didnt respond correctly")
        )
        event <- parseGptResponse(gptResponseStr)
      } yield event
      either <- event.attempt
      res <- either match {
        case Left(er)     => IO.println("unsuccessful request because " + er.getMessage) *> IO(None)
        case Right(event) => IO(Some(event))
      }
    } yield res
  }

  private def userTimeout(id: Long, newTime: Long): IO[FiniteDuration] = {
    for {
      timeOpt <- db.getTime(id)
      res <- timeOpt match {
        case Some(time) =>
          IO(Duration(Math.max(config.userTimeout - (newTime - time), 0), SECONDS))
        case None => IO(Duration.Zero)
      }
      _ <- if (res.toSeconds == 0) db.updateTime(id, newTime) else IO()
    } yield res
  }

  private def formEventAddedMsg(event: Event, timeZone: Int): String = {
    import Extensions._
    s"${event.topic} planned at ${event.time.weekdayWithTimezone(timeZone)}"
  }

  def toNewEvent(msg: TextMessage)(implicit
    client: TelegramClient[IO]
  ): IO[Unit] =
    for {
      _        <- IO.println(s"Received message from: ${msg.from}, chat id: ${msg.chat.id}")
      duration <- userTimeout(msg.chat.id, msg.date)
      _ <-
        if (duration.toSeconds == 0) {
          for {
            _        <- msg.chat.send("Handling your request... be patient")
            timezone <- db.getTimezone(msg.chat.id)
            eventOpt <- tryToReplyN(makePrompt(msg.text, timezone), config.gptRetries)
            eventUtc <- IO(
              eventOpt.map(e => Event(e.topic, e.time.minus(Duration(timezone, SECONDS).toMillis)))
            )
            _ <- eventUtc match {
              case Some(event) =>
                val eventMessage = formEventAddedMsg(event, timezone)
                db.addEvent(DBEvent(-1, msg.chat.id, event.time.clicks, event.topic)) *>
                  IO.println(eventMessage) *>
                  msg.chat.send(eventMessage)
              case None =>
                msg.chat.send(
                  "Couldn't parse this event, make sure there is a date specified and try again."
                )
            }
          } yield ()
        } else msg.chat.send(s"Not so fast! Try again in ${duration.toSeconds} seconds")
    } yield ()

  def toLocation(msg: LocationMessage)(implicit
    client: TelegramClient[IO]
  ): IO[Unit] =
    for {
      zone <- timezoneManager.getTimezone(msg.location)
      _    <- db.updateTimezone(msg.chat.id, zone.utcOffsetSeconds)
      _    <- msg.chat.send("Your timezone is set to: " + zone.ianaTimeId)
    } yield ()

  def toRemoveLast(msg: TelegramMessage)(implicit
    client: TelegramClient[IO]
  ): IO[Unit] =
    for {
      topicOpt <- db.deleteLastAddedEvent(msg.chat.id)
      _ <- topicOpt match {
        case Some(topic) => msg.chat.send("Removed " + topic)
        case None        => msg.chat.send("You don't have any events planned")
      }
    } yield ()

  def toRemove(msg: TextMessage)(implicit
    client: TelegramClient[IO]
  ): IO[Unit] = {
    val topic = msg.text.split(' ') match {
      case ar if ar.length == 1 => None
      case ar                   => Some(ar.tail.reduce((x, y) => x + ' ' + y))
    }
    for {
      removed <- topic match {
        case Some(s) => db.deleteEventByName(msg.chat.id, s)
        case None    => IO(None)
      }
      _ <- (removed, topic) match {
        case (Some(_), Some(txt)) => msg.chat.send("Removed " + txt)
        case (_, None)            => msg.chat.send("You didn't provide topic name")
        case (None, _)            => msg.chat.send("This event doesn't exist")
      }
    } yield ()
  }

  def toStart(msg: TelegramMessage)(implicit
    client: TelegramClient[IO]
  ): IO[Unit] =
    msg.chat
      .send(
        "Hi!\nI can remind you about any event, just send me description and time of it (possibly with time zone)\n" +
          "To start send me your location (any location in your time zone) or UTC will be used by default"
      )
      .void

}
