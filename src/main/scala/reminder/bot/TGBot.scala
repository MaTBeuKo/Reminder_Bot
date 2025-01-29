package reminder.bot

import canoe.api.{Bot, Scenario, TelegramClient, chatApi}
import canoe.methods.messages.SendMessage
import canoe.models.ChatId
import canoe.models.messages.TextMessage
import canoe.syntax._
import cats.effect.IO
import fs2.Stream
import reminder.api.GptManager
import reminder.dao.DataBase
import sttp.client3.SttpBackend
import canoe.syntax._

case class BotConfig(
  token: String,
  gptCount: Int,
  gptRetries: Int,
  userTimeout: Long,
  bdcKey: String
)

class TGBot(manager: GptManager, config: BotConfig, db: DataBase, backend: SttpBackend[IO, Any]) {

  private val helper = new ResponseService(manager, config, db, backend)
  private val client = TelegramClient[IO](config.token)

  def sendMessage(chatId: Long, text: String): IO[Unit] =
    client.use(implicit c =>
      SendMessage(ChatId(chatId), if (text.nonEmpty) text else "^_^").call.void
    )

  private def location(implicit
    telegramClient: TelegramClient[IO]
  ): Scenario[IO, Unit] =
    for {
      msg <- Scenario.expect(locationMessage)
      _   <- Scenario.eval(helper.toLocation(msg))
    } yield ()

  private val textNotCommand: Expect[TextMessage] = {
    case m: TextMessage if !m.text.startsWith("/") => m
  }

  private def event(implicit
    telegramClient: TelegramClient[IO]
  ): Scenario[IO, Unit] =
    for {
      msg <- Scenario.expect(textNotCommand)
      _   <- Scenario.eval(helper.toNewEvent(msg))
    } yield ()

  private def start(implicit
    telegramClient: TelegramClient[IO]
  ): Scenario[IO, Unit] =
    for {
      msg <- Scenario.expect(command("start"))
      _   <- Scenario.eval(helper.toStart(msg))
    } yield ()

  private def remove(implicit
    telegramClient: TelegramClient[IO]
  ): Scenario[IO, Unit] =
    for {
      msg <- Scenario.expect(command("pop"))
      _   <- Scenario.eval(helper.toRemoveLast(msg))
    } yield ()

  private def removeTopic(implicit
    telegramClient: TelegramClient[IO]
  ): Scenario[IO, Unit] =
    for {
      msg <- Scenario.expect(command("remove"))
      _   <- Scenario.eval(helper.toRemove(msg))
    } yield ()

  def run: IO[Unit] =
    Stream
      .resource(client)
      .flatMap(implicit client =>
        Bot.polling[IO].follow(remove, start, event, location, removeTopic)
      )
      .compile
      .drain

}

object TGBot {

  def apply(
    manager: GptManager,
    config: BotConfig,
    db: DataBase,
    backend: SttpBackend[IO, Any]
  ): IO[TGBot] =
    for {
      res <- IO(new TGBot(manager, config, db, backend))
    } yield res

}
