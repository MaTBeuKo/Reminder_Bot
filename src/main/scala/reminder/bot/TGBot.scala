package reminder.bot

import canoe.api.{Bot, Scenario, TelegramClient}
import canoe.methods.messages.SendMessage
import canoe.models.ChatId
import canoe.models.messages.{TelegramMessage, TextMessage}
import canoe.syntax._
import cats.{Monad, MonadThrow}
import cats.effect.{Async, Concurrent}
import cats.implicits._
import org.typelevel.log4cats.LoggerFactory
import reminder.persistence.DataBase
import reminder.gpt.GptProvider
import reminder.syntax.Pretty
import sttp.client3.SttpBackend

import scala.language.higherKinds

case class BotConfig(
  token: String,
  gptCount: Int,
  gptRetries: Int,
  userTimeout: Long,
  bdcKey: String,
  defaultTimezone: Int
)

trait Send[F[_]] {
  def sendMessage(chatId: Long, text: String): F[Unit]
}

object Send {

  def apply[F[_]: TelegramClient: Monad: LoggerFactory]: Send[F] =
    (chatId: Long, text: String) =>
      SendMessage(ChatId(chatId), text).call.flatMap(msg =>
        LoggerFactory[F].getLogger.info("Message sent to: " + Pretty.prettyUser(msg.chat))
      )

}

class TGBot[F[_]: TelegramClient: MonadThrow: LoggerFactory] private (
  respond: ResponseService[F]
) {

  def sendMessage(chatId: Long, text: String): F[Unit] = Send[F].sendMessage(chatId, text)

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
      LoggerFactory[F].getLogger.info("Received message from: " + Pretty.prettyUser(msg.chat))
  )

  def run(implicit
    C: Concurrent[F]
  ): F[Unit] =
    Bot.polling[F].follow(pop, start, event, location, remove, logAny).compile.drain

}

object TGBot {

  def apply[F[_]: Async: LoggerFactory](
    manager: GptProvider[F],
    config: BotConfig,
    db: DataBase[F],
    backend: SttpBackend[F, Any],
    client: TelegramClient[F]
  ): F[TGBot[F]] =
    Async[F].delay({
      implicit val tg: TelegramClient[F] = client
      val resp: ResponseService[F] =
        ResponseService[F](
          EventMaker[F](manager, config),
          config,
          db,
          backend,
          Send[F]
        )
      new TGBot(resp)(tg, Async[F], LoggerFactory[F])
    })

}
