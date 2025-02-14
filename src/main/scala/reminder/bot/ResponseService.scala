package reminder.bot

import canoe.models.messages.{LocationMessage, TextMessage}
import cats.MonadThrow
import cats.effect.Async
import cats.implicits._
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import reminder.api.TimezoneApi
import reminder.bot.talk.SayInEnglish
import reminder.persistence.{DBEvent, DataBase}
import reminder.notifier.Event
import sttp.client3.SttpBackend

import java.sql.SQLException
import scala.concurrent.duration.{Duration, FiniteDuration, SECONDS}
import scala.language.higherKinds

trait ResponseService[F[_]] {

  def toNewEvent(msg: TextMessage): F[Unit]

  def toLocation(msg: LocationMessage): F[Unit]

  def toPop(msg: TextMessage): F[Unit]

  def toRemove(msg: TextMessage): F[Unit]

  def toStart(msg: TextMessage): F[Unit]

  def handleFatal(chatId: Long, er: Throwable): F[Unit]

}

object ResponseService {

  def apply[F[_]: LoggerFactory](
    eventMaker: EventMaker[F],
    config: BotConfig,
    db: DataBase[F],
    backend: SttpBackend[F, Any],
    send: Send[F]
  )(implicit
    M: MonadThrow[F]
  ): ResponseService[F] = new ResponseService[F] {
    private val log: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger
    private val timezoneManager                   = TimezoneApi(config.bdcKey, backend)
    private val say                               = new SayInEnglish

    private def userTimeout(id: Long, newTime: Long): F[FiniteDuration] = {
      for {
        timeOpt <- db.getTime(id)
        res <- timeOpt match {
          case Some(time) =>
            M.pure(Duration(Math.max(config.userTimeout - (newTime - time), 0), SECONDS))
          case None => M.pure(Duration.Zero)
        }
        _ <- if (res.toSeconds == 0) db.updateTime(id, newTime) else M.pure()
      } yield res
    }

    override def handleFatal(chatId: Long, er: Throwable): F[Unit] = er match {
      case ex: SQLException =>
        log.info("Fatal error on DB side: " + ex.getMessage) *> send.sendMessage(
          chatId,
          say.fatalError
        )
      case _ => M.raiseError(er)
    }

    override def toNewEvent(msg: TextMessage): F[Unit] =
      for {
        _        <- log.info(s"Received message from: ${msg.from}, chat id: ${msg.chat.id}")
        duration <- userTimeout(msg.chat.id, msg.date)
        _ <-
          if (duration.toSeconds == 0) {
            for {
              _        <- send.sendMessage(msg.chat.id, say.handlingEvent)
              timezone <- db.getTimezone(msg.chat.id).map(_.getOrElse(config.defaultTimezone))
              eventOpt <- eventMaker.attemptN(
                eventMaker.makePrompt(msg.text, timezone),
                config.gptRetries
              )
              eventUtc <- M.pure(
                eventOpt.map(e =>
                  Event(e.topic, e.time.minus(Duration(timezone, SECONDS).toMillis))
                )
              )
              _ <- eventUtc match {
                case Some(event) =>
                  val eventPlannedMsg = say.eventPlanned(event, timezone)
                  db.addEvent(DBEvent(-1, msg.chat.id, event.time.clicks, event.topic)) *>
                    log.info(eventPlannedMsg) *>
                    send.sendMessage(msg.chat.id, eventPlannedMsg)
                case None =>
                  send.sendMessage(
                    msg.chat.id,
                    say.badEvent
                  )
              }
            } yield ()
          } else send.sendMessage(msg.chat.id, say.notSoFast(duration))
      } yield ()

    override def toLocation(msg: LocationMessage): F[Unit] =
      for {
        zone <- timezoneManager.getTimezone(msg.location)
        _    <- db.updateTimezone(msg.chat.id, zone.utcOffsetSeconds)
        _    <- send.sendMessage(msg.chat.id, say.timezoneSet(zone.ianaTimeId))
      } yield ()

    override def toPop(msg: TextMessage): F[Unit] =
      for {
        topicOpt <- db.deleteLastAddedEvent(msg.chat.id)
        _ <- topicOpt match {
          case Some(topic) => send.sendMessage(msg.chat.id, say.eventRemoved(topic))
          case None        => send.sendMessage(msg.chat.id, say.noEvents)
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
          case (Some(_), Some(txt)) => send.sendMessage(msg.chat.id, say.eventRemoved(txt))
          case (_, None)            => send.sendMessage(msg.chat.id, say.provideTopic)
          case (None, _)            => send.sendMessage(msg.chat.id, say.noSuchEvent)
        }
      } yield ()
    }

    override def toStart(msg: TextMessage): F[Unit] =
      send.sendMessage(msg.chat.id, say.start(config.defaultTimezone)).void
  }

}
