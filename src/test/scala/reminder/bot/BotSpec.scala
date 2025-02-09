package reminder.bot

import akka.http.scaladsl.model.DateTime
import canoe.models.PrivateChat
import canoe.models.messages.TextMessage
import cats.MonadError
import cats.effect.{Async, IO, Temporal}
import cats.effect.unsafe.implicits.global
import org.mockito.ArgumentMatchers.{any, anyInt, anyLong, anyString}
import org.mockito.{ArgumentMatchers, MockitoSugar}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers._
import reminder.api.GptProvider
import reminder.bot.talk.SayInEnglish
import reminder.dao.DataBase
import reminder.notifier.Event
import sttp.client3.SttpBackend
import cats._
import cats.data.{EitherT, OptionT}
import cats.effect.implicits.genSpawnOps_
import cats.syntax.all._
import io.circe.{Json, parser}

import scala.concurrent.duration.Duration
import scala.language.higherKinds

class BotSpec extends AnyFlatSpec with should.Matchers with MockitoSugar {

  val say = new SayInEnglish

  def makeMsg(text: String): TextMessage = {
    val chat = PrivateChat(1, Some("Ivan"), None, None)
    TextMessage(1, chat, 5, text)
  }

  trait Service {

    val msg      = makeMsg("/remove sample")
    val managerS = mock[GptProvider]
    val makerS   = mock[EventMaker]
    val configS  = mock[BotConfig]
    val backendS = mock[SttpBackend[IO, Any]]
    val sendS    = mock[(Long, String) => IO[Unit]]
    when(sendS.apply(any(), any())).thenReturn(IO.unit)

  }

  trait Db1 {

    val dbS = mock[DataBase]
    when(dbS.deleteEventByName(1, "sample")).thenReturn(IO.pure(Some()))
    when(dbS.deleteLastAddedEvent(any())).thenReturn(IO(Some("sample")))

  }

  trait DbEmpty {

    val dbS = mock[DataBase]
    when(dbS.deleteEventByName(any(), any())).thenReturn(IO.none)
    when(dbS.deleteLastAddedEvent(any())).thenReturn(IO.none)

  }

  "remove" should "send success telegram message when event present" in new Service with Db1 {
    val service = new ResponseServiceImpl(makerS, configS, dbS, backendS, sendS)
    service.toRemove(msg).unsafeRunSync()
    verify(sendS, times(1)).apply(1, say.eventRemoved("sample"))
  }

  "remove" should "send error telegram message if no event present" in new Service with DbEmpty {
    val service = new ResponseServiceImpl(makerS, configS, dbS, backendS, sendS)
    service.toRemove(msg).unsafeRunSync()
    verify(sendS, times(1)).apply(1, say.noSuchEvent)
  }

  "pop" should "send success telegram message when some event present" in new Service with Db1 {
    val service = new ResponseServiceImpl(makerS, configS, dbS, backendS, sendS)
    service.toPop(msg).unsafeRunSync()
    verify(sendS, times(1)).apply(1, say.eventRemoved("sample"))
  }

  "pop" should "send error telegram message if no event present" in new Service with DbEmpty {
    val service = new ResponseServiceImpl(makerS, configS, dbS, backendS, sendS)
    service.toPop(msg).unsafeRunSync()
    verify(sendS, times(1)).apply(1, say.noEvents)
  }

  trait DbNeutral {

    val dbS = mock[DataBase]
    when(dbS.deleteEventByName(any(), any())).thenReturn(IO.none)
    when(dbS.deleteLastAddedEvent(any())).thenReturn(IO.none)
    when(dbS.getTime(anyLong())).thenReturn(IO.pure(Some(0)))
    when(dbS.updateTime(anyLong(), anyLong())).thenReturn(IO())
    when(dbS.getTimezone(anyLong())).thenReturn(IO.pure(Some(0)))
    when(dbS.addEvent(any())).thenReturn(IO())

  }

  "toNewEvent" should "send handling and success telegram message for correct event" in new Service
    with DbNeutral {
    val plainEvent = "{\"topic\":\"sample\",\"time\":\"01-01T12:00:00\"}"
    val eventIO    = Event.fromJson(parser.parse(plainEvent).getOrElse(throw new Exception()))
    val event      = eventIO.unsafeRunSync()
    when(makerS.attemptN(anyString(), anyInt())).thenReturn(eventIO.map(Some(_)))
    val service = new ResponseServiceImpl(makerS, configS, dbS, backendS, sendS)
    service.toNewEvent(msg).unsafeRunSync()
    verify(sendS, times(1)).apply(1, say.handlingEvent)
    verify(sendS, times(1)).apply(1, say.eventPlanned(event, 0))
  }

}
