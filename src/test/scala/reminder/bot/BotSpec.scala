package reminder.bot

import canoe.models.PrivateChat
import canoe.models.messages.TextMessage
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser
import org.mockito.ArgumentMatchers.{any, anyInt, anyLong, anyString}
import org.mockito.MockitoSugar
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers._
import org.typelevel.log4cats.slf4j.loggerFactoryforSync
import reminder.bot.talk.SayInEnglish
import reminder.persistence.DataBase
import reminder.gpt.GptProvider
import reminder.notifier.Event
import sttp.client3.SttpBackend

import scala.language.higherKinds

class BotSpec extends AnyFlatSpec with should.Matchers with MockitoSugar {

  val say = new SayInEnglish

  def makeMsg(text: String): TextMessage = {
    val chat = PrivateChat(1, Some("Ivan"), None, None)
    TextMessage(1, chat, 5, text)
  }

  trait Service {

    val msg      = makeMsg("/remove sample")
    val managerS = mock[GptProvider[IO]]
    val makerS   = mock[EventMaker[IO]]
    val configS  = mock[BotConfig]
    val backendS = mock[SttpBackend[IO, Any]]
    val sendS    = mock[Send[IO]]
    when(sendS.sendMessage(any(), any())).thenReturn(IO.unit)

  }

  trait Db1 {

    val dbS = mock[DataBase[IO]]
    when(dbS.deleteEventByName(1, "sample")).thenReturn(IO.pure(Some()))
    when(dbS.deleteLastAddedEvent(any())).thenReturn(IO(Some("sample")))

  }

  trait DbEmpty {

    val dbS = mock[DataBase[IO]]
    when(dbS.deleteEventByName(any(), any())).thenReturn(IO.none)
    when(dbS.deleteLastAddedEvent(any())).thenReturn(IO.none)

  }

  "remove" should "send success telegram message when event present" in new Service with Db1 {
    val service = ResponseService(makerS, configS, dbS, backendS, sendS)
    service.toRemove(msg).unsafeRunSync()
    verify(sendS, times(1)).sendMessage(1, say.eventRemoved("sample"))
  }

  "remove" should "send error telegram message if no event present" in new Service with DbEmpty {
    val service = ResponseService(makerS, configS, dbS, backendS, sendS)
    service.toRemove(msg).unsafeRunSync()
    verify(sendS, times(1)).sendMessage(1, say.noSuchEvent)
  }

  "pop" should "send success telegram message when some event present" in new Service with Db1 {
    val service = ResponseService(makerS, configS, dbS, backendS, sendS)
    service.toPop(msg).unsafeRunSync()
    verify(sendS, times(1)).sendMessage(1, say.eventRemoved("sample"))
  }

  "pop" should "send error telegram message if no event present" in new Service with DbEmpty {
    val service = ResponseService(makerS, configS, dbS, backendS, sendS)
    service.toPop(msg).unsafeRunSync()
    verify(sendS, times(1)).sendMessage(1, say.noEvents)
  }

  trait DbNeutral {

    val dbS = mock[DataBase[IO]]
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
    val event      = Event.fromJson(parser.parse(plainEvent).getOrElse(throw new Exception()))
    when(makerS.attemptN(anyString(), anyInt())).thenReturn(IO.pure(event))
    val service = ResponseService(makerS, configS, dbS, backendS, sendS)
    service.toNewEvent(msg).unsafeRunSync()
    verify(sendS, times(1)).sendMessage(1, say.handlingEvent)
    verify(sendS, times(1)).sendMessage(1, say.eventPlanned(event.get, 0))
  }

}
