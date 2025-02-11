package reminder.bot

import canoe.models.messages.{LocationMessage, TextMessage}
import cats.effect.IO
import org.postgresql.util.PSQLException
import reminder.api.{GptProvider, TimezoneManager}
import reminder.bot.talk.SayInEnglish
import reminder.dao.{DBEvent, DataBase}
import reminder.notifier.Event
import sttp.client3.SttpBackend

import java.sql.SQLException
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}

class ResponseServiceImpl(
  eventMaker: EventMaker,
  config: BotConfig,
  db: DataBase[IO],
  backend: SttpBackend[IO, Any],
  send: (Long, String) => IO[Unit]
) extends ResponseService {

  private val timezoneManager = new TimezoneManager(config.bdcKey, backend)
  private val say             = new SayInEnglish

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

  def handleFatal(chatId: Long, er: Throwable): IO[Unit] = er match {
    case ex: SQLException =>
      IO.println("Fatal error on DB side: " + ex.getMessage) *> send(chatId, say.fatalError)
    case _ => IO.raiseError(er)
  }

  override def toNewEvent(msg: TextMessage): IO[Unit] =
    for {
      _        <- IO.println(s"Received message from: ${msg.from}, chat id: ${msg.chat.id}")
      duration <- userTimeout(msg.chat.id, msg.date)
      _ <-
        if (duration.toSeconds == 0) {
          for {
            _        <- send(msg.chat.id, say.handlingEvent)
            timezone <- db.getTimezone(msg.chat.id).map(_.getOrElse(0))
            eventOpt <- eventMaker.attemptN(
              eventMaker.makePrompt(msg.text, timezone),
              config.gptRetries
            )
            eventUtc <- IO(
              eventOpt.map(e => Event(e.topic, e.time.minus(Duration(timezone, SECONDS).toMillis)))
            )
            _ <- eventUtc match {
              case Some(event) =>
                val eventPlannedMsg = say.eventPlanned(event, timezone)
                db.addEvent(DBEvent(-1, msg.chat.id, event.time.clicks, event.topic)) *>
                  IO.println(eventPlannedMsg) *>
                  send(msg.chat.id, eventPlannedMsg)
              case None =>
                send(
                  msg.chat.id,
                  say.badEvent
                )
            }
          } yield ()
        } else send(msg.chat.id, say.notSoFast(duration))
    } yield ()

  override def toLocation(msg: LocationMessage): IO[Unit] =
    for {
      zone <- timezoneManager.getTimezone(msg.location)
      _    <- db.updateTimezone(msg.chat.id, zone.utcOffsetSeconds)
      _    <- send(msg.chat.id, say.timezoneSet(zone.ianaTimeId))
    } yield ()

  override def toPop(msg: TextMessage): IO[Unit] =
    for {
      topicOpt <- db.deleteLastAddedEvent(msg.chat.id)
      _ <- topicOpt match {
        case Some(topic) => send(msg.chat.id, say.eventRemoved(topic))
        case None        => send(msg.chat.id, say.noEvents)
      }
    } yield ()

  override def toRemove(msg: TextMessage): IO[Unit] = {
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
        case (Some(_), Some(txt)) => send(msg.chat.id, say.eventRemoved(txt))
        case (_, None)            => send(msg.chat.id, say.provideTopic)
        case (None, _)            => send(msg.chat.id, say.noSuchEvent)
      }
    } yield ()
  }

  override def toStart(msg: TextMessage): IO[Unit] = send(msg.chat.id, say.start).void

  override def toRu(msg: TextMessage): IO[Unit] = ???
  override def toEn(msg: TextMessage): IO[Unit] = ???

}
