package reminder.bot

import canoe.models.messages.{LocationMessage, TextMessage}
import cats.effect.Async
import cats.implicits._
import reminder.api.TimezoneManager
import reminder.bot.talk.SayInEnglish
import reminder.dao.{DBEvent, DataBase}
import reminder.notifier.Event
import sttp.client3.SttpBackend

import java.sql.SQLException
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import scala.language.higherKinds

class ResponseServiceImpl[F[+_]](
  eventMaker: EventMaker[F],
  config: BotConfig,
  db: DataBase[F],
  backend: SttpBackend[F, Any],
  send: (Long, String) => F[Unit]
)(implicit
  M: Async[F]
) extends ResponseService {

  private val timezoneManager = new TimezoneManager(config.bdcKey, backend)
  private val say             = new SayInEnglish

  private def userTimeout(id: Long, newTime: Long): F[FiniteDuration] = {
    for {
      timeOpt <- db.getTime(id)
      res <- timeOpt match {
        case Some(time) =>
          M.delay(Duration(Math.max(config.userTimeout - (newTime - time), 0), SECONDS))
        case None => M.delay(Duration.Zero)
      }
      _ <- if (res.toSeconds == 0) db.updateTime(id, newTime) else M.pure()
    } yield res
  }

  def handleFatal(chatId: Long, er: Throwable): F[Unit] = er match {
    case ex: SQLException =>
      M.delay(println("Fatal error on DB side: " + ex.getMessage)) *> send(chatId, say.fatalError)
    case _ => M.raiseError(er)
  }

  override def toNewEvent(msg: TextMessage): F[Unit] =
    for {
      _        <- M.delay(println(s"Received message from: ${msg.from}, chat id: ${msg.chat.id}"))
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
            eventUtc <- M.delay(
              eventOpt.map(e => Event(e.topic, e.time.minus(Duration(timezone, SECONDS).toMillis)))
            )
            _ <- eventUtc match {
              case Some(event) =>
                val eventPlannedMsg = say.eventPlanned(event, timezone)
                db.addEvent(DBEvent(-1, msg.chat.id, event.time.clicks, event.topic)) *>
                  M.delay(println(eventPlannedMsg)) *>
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

  override def toLocation(msg: LocationMessage): F[Unit] =
    for {
      zone <- timezoneManager.getTimezone(msg.location)
      _    <- db.updateTimezone(msg.chat.id, zone.utcOffsetSeconds)
      _    <- send(msg.chat.id, say.timezoneSet(zone.ianaTimeId))
    } yield ()

  override def toPop(msg: TextMessage): F[Unit] =
    for {
      topicOpt <- db.deleteLastAddedEvent(msg.chat.id)
      _ <- topicOpt match {
        case Some(topic) => send(msg.chat.id, say.eventRemoved(topic))
        case None        => send(msg.chat.id, say.noEvents)
      }
    } yield ()

  override def toRemove(msg: TextMessage): F[Unit] = {
    val topic = msg.text.split(' ') match {
      case ar if ar.length == 1 => None
      case ar                   => Some(ar.tail.reduce((x, y) => x + ' ' + y))
    }
    for {
      removed <- topic match {
        case Some(s) => db.deleteEventByName(msg.chat.id, s)
        case None    => M.pure(None)
      }
      _ <- (removed, topic) match {
        case (Some(_), Some(txt)) => send(msg.chat.id, say.eventRemoved(txt))
        case (_, None)            => send(msg.chat.id, say.provideTopic)
        case (None, _)            => send(msg.chat.id, say.noSuchEvent)
      }
    } yield ()
  }

  override def toStart(msg: TextMessage): F[Unit] = send(msg.chat.id, say.start).void

}
