package reminder.bot

import canoe.api.{Bot, Scenario, TelegramClient}
import canoe.methods.messages.SendMessage
import canoe.models.ChatId
import canoe.models.messages.{TelegramMessage, TextMessage}
import canoe.syntax._
import cats.effect.IO
import reminder.api.GptProvider
import reminder.dao.DataBase
import reminder.syntax.Pretty
import sttp.client3.SttpBackend

import scala.language.higherKinds

case class BotConfig(
  token: String,
  gptCount: Int,
  gptRetries: Int,
  userTimeout: Long,
  bdcKey: String
)

class TGBot(manager: GptProvider, config: BotConfig, db: DataBase, backend: SttpBackend[IO, Any])(
  implicit
  tg: TelegramClient[IO]
) {

  private val respond: ResponseService =
    new ResponseServiceImpl(new EventMaker(manager, config), config, db, backend, sendMessage)

  def sendMessage(chatId: Long, text: String): IO[Unit] =
    SendMessage(ChatId(chatId), text).call.flatMap(msg =>
      IO.println("Message sent to: " + Pretty.prettyUser(msg.chat))
    )

  private def bind[T <: TelegramMessage](
    pattern: Expect[T],
    handler: T => IO[Unit]
  ): Scenario[IO, Unit] =
    for {
      msg <- Scenario.expect(pattern)
      _   <- Scenario.eval(handler(msg).handleErrorWith(respond.handleFatal(msg.chat.id, _)))
    } yield ()

  private def location: Scenario[IO, Unit] = bind(locationMessage, respond.toLocation)

  private val textNotCommand: Expect[TextMessage] = {
    case m: TextMessage if !m.text.startsWith("/") => m
  }

  private def event: Scenario[IO, Unit] = bind(textNotCommand, respond.toNewEvent)

  private def start: Scenario[IO, Unit] = bind(command("start"), respond.toStart)

  private def pop: Scenario[IO, Unit] = bind(command("pop"), respond.toPop)

  private def remove: Scenario[IO, Unit] = bind(command("remove"), respond.toRemove)

  private def logAny: Scenario[IO, Unit] = bind(
    any,
    (msg: TelegramMessage) => IO.println("Received message from: " + Pretty.prettyUser(msg.chat))
  )

  def run: IO[Unit] =
    Bot.polling[IO].follow(pop, start, event, location, remove, logAny).compile.drain

}

object TGBot {

  def apply(
    manager: GptProvider,
    config: BotConfig,
    db: DataBase,
    backend: SttpBackend[IO, Any],
    client: TelegramClient[IO]
  ): IO[TGBot] =
    IO(new TGBot(manager, config, db, backend)(client))

}
