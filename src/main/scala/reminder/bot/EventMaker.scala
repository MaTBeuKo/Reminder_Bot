package reminder.bot

import akka.http.scaladsl.model.DateTime
import cats.effect.Temporal
import cats.implicits._
import io.circe.parser
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}
import reminder.gpt.GptProvider
import reminder.notifier.Event

import scala.language.higherKinds

trait EventMaker[F[_]] {

  def makePrompt(text: String, tzOffset: Int): String
  def attemptN(prompt: String, count: Int): F[Option[Event]]

}

object EventMaker {

  def apply[F[_]: LoggerFactory](manager: GptProvider[F], config: BotConfig)(implicit
    M: Temporal[F]
  ): EventMaker[F] = new EventMaker[F] {
    val log: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger
    def makePrompt(text: String, tzOffset: Int): String = {
      import reminder.syntax.Pretty._
      val tz        = prettyTz(tzOffset)
      val localDate = DateTime.now.weekdayWithTimezone(tzOffset)
      "Extract topic, date and time of the event described in the end. " +
        s"Convert date and time to $tz. Don't indicate year. Current time is: " +
        localDate + ". " +
        s"Dont add anything else, your reply must be exactly in this format. Convert resulting time to $tz." +
        "{\"topic\":\"topic name\",\"time\":\"mm-ddThh:mm:ss\"}\nEvent:\n" + text
    }

    def attemptN(prompt: String, count: Int): F[Option[Event]] = {
      import retry._
      retryingOnFailures[Option[Event]](
        RetryPolicies.limitRetries[F](count),
        opt => M.pure(opt.nonEmpty),
        (_, y) => if (y.givingUp) log.info("too many retries, giving up") else M.pure()
      )(attempt(prompt))
    }

    private def attempt(prompt: String): F[Option[Event]] = {
      for {
        gptResponseStrOpt <- manager
          .ask(prompt, config.gptCount, parseGptResponse(_).nonEmpty)
          .value
          .handleErrorWith(er => log.info("ask throw exception: " + er) *> M.pure(None))
        event = for {
          gptResponseStr <- gptResponseStrOpt
          event          <- parseGptResponse(gptResponseStr)
        } yield event
        res <- event match {
          case None    => log.info("unsuccessful attempt") *> M.pure(None)
          case Some(_) => M.pure(event)
        }
      } yield res
    }

    private def parseGptResponse(text: String): Option[Event] =
      for {
        json  <- parser.parse(text).toOption
        event <- Event.fromJson(json)
      } yield event
  }

}
