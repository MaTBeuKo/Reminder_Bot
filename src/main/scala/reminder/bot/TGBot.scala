package reminder.bot

import canoe.api.{Bot, Scenario, TelegramClient}
import canoe.methods.messages.SendMessage
import canoe.models.ChatId
import canoe.models.messages.{TelegramMessage, TextMessage}
import canoe.syntax._
import cats.effect.Async
import reminder.api.GptProvider
import reminder.dao.DataBase
import reminder.syntax.Pretty
import sttp.client3.SttpBackend
import cats.implicits._
import scala.language.higherKinds

case class BotConfig(
  token: String,
  gptCount: Int,
  gptRetries: Int,
  userTimeout: Long,
  bdcKey: String
)

class TGBot[F[+_]](
  manager: GptProvider[F],
  config: BotConfig,
  db: DataBase[F],
  backend: SttpBackend[F, Any]
)(implicit
  tg: TelegramClient[F],
  M: Async[F]
) {

  private val respond: ResponseService[F] =
    new ResponseServiceImpl[F](
      new EventMaker(manager, config),
      config,
      db,
      backend,
      sendMessage
    )

  def sendMessage(chatId: Long, text: String): F[Unit] =
    SendMessage(ChatId(chatId), text).call.flatMap(msg =>
      M.delay(println("Message sent to: " + Pretty.prettyUser(msg.chat)))
    )

  private def bind[T <: TelegramMessage](
    pattern: Expect[T],
    handler: T => F[Unit]
  ): Scenario[F, Unit] =
    for {
      msg <- Scenario.expect(pattern)
      _   <- Scenario.eval(handler(msg).handleErrorWith(respond.handleFatal(msg.chat.id, _)))
    } yield ()

  private def location: Scenario[F, Unit] = bind(locationMessage, respond.toLocation)

  private val textNotCommand: Expect[TextMessage] = {
    case m: TextMessage if !m.text.startsWith("/") => m
  }

  private def event: Scenario[F, Unit] = bind(textNotCommand, respond.toNewEvent)

  private def start: Scenario[F, Unit] = bind(command("start"), respond.toStart)

  private def pop: Scenario[F, Unit] = bind(command("pop"), respond.toPop)

  private def remove: Scenario[F, Unit] = bind(command("remove"), respond.toRemove)

  private def logAny: Scenario[F, Unit] = bind(
    any,
    (msg: TelegramMessage) =>
      M.delay(println("Received message from: " + Pretty.prettyUser(msg.chat)))
  )

  def run: F[Unit] =
    Bot.polling[F].follow(pop, start, event, location, remove, logAny).compile.drain

}

object TGBot {

  def apply[F[+_]: Async](
    manager: GptProvider[F],
    config: BotConfig,
    db: DataBase[F],
    backend: SttpBackend[F, Any],
    client: TelegramClient[F]
  ): F[TGBot[F]] =
    Async[F].delay(new TGBot(manager, config, db, backend)(client, Async[F]))

}
