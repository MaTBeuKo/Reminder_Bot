package reminder

import akka.http.scaladsl.model.DateTime
import canoe.api.TelegramClient
import cats.Monad
import cats.effect.{ExitCode, IO, IOApp, Resource}
import org.asynchttpclient.Dsl.asyncHttpClient
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import org.typelevel.log4cats.slf4j.loggerFactoryforSync
import pureconfig.ConfigConvert.fromReaderAndWriter
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import reminder.api.Gpt4freeApi
import reminder.bot.{BotConfig, Send, TGBot}
import reminder.persistence.{DBConfig, DataBaseImpl}
import reminder.gpt.{GPTConfig, Gpt4free}
import reminder.notifier.Notifier
import sttp.client3._
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend

import scala.language.higherKinds

case class Configuration(bot: BotConfig, gpt: GPTConfig, database: DBConfig)

object Main extends IOApp {

  private def makeAsyncClient: Resource[IO, SttpBackend[IO, Any]] = Resource.make({
    IO(AsyncHttpClientCatsBackend.usingClient[IO](asyncHttpClient()))
  })({ client => IO(client.close()) })

  private def configFromArgs(args: List[String]) =
    args.headOption match {
      case Some(arg) => ConfigSource.string("token = \"" + arg + "\"")
      case None      => ConfigSource.string("")
    }

  val log: SelfAwareStructuredLogger[IO] = LoggerFactory[IO].getLogger

  override def run(args: List[String]): IO[ExitCode] = {
    for {
      config <- IO(
        ConfigSource
          .file("/run/secrets/bot")
          .optional
          .withFallback(configFromArgs(args).optional)
          .withFallback(ConfigSource.file("src/main/resources/bot.conf").optional)
          .load[Configuration]
      )
      startIO <- config match {
        case Right(cfg) =>
          (
            for {
              asyncBackend <- makeAsyncClient
              tgClient     <- TelegramClient[IO](cfg.bot.token)
            } yield (asyncBackend, tgClient)
          ).use { case (asyncBackend, tgClient) =>
            for {
              db <- DataBaseImpl[IO](cfg.database)
              bot <- TGBot[IO](
                Gpt4free[IO](Gpt4freeApi(asyncBackend, cfg.gpt), cfg.gpt),
                cfg.bot,
                db,
                asyncBackend,
                tgClient
              )
              _ <- Notifier.run(Send[IO](tgClient, Monad[IO], LoggerFactory[IO]), db).start
              _ <- log.info(s"reminder-bot started at ${DateTime.now.toIsoLikeDateTimeString()}")
              start <- bot.run
                .as(ExitCode.Success)
            } yield start
          }
        case Left(ex) =>
          log.error(
            "Error, provide telegram bot api-key as an argument to run this bot, or use docker image with secret named 'bot' " +
              "also make sure bot.conf exists and configured"
          ) *> log.error(ex.prettyPrint()).as(ExitCode.Error)
      }
    } yield startIO
  }

}
