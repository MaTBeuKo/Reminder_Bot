package reminder.bot

import akka.http.scaladsl.model.DateTime
import cats.effect.Async
import cats.implicits._
import io.circe.parser
import reminder.api.GptProvider
import reminder.notifier.Event

import scala.language.higherKinds

class EventMaker[F[+_]](manager: GptProvider[F], config: BotConfig)(implicit
  M: Async[F]
) {

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
      opt => M.delay(opt.nonEmpty),
      (_, y) => if (y.givingUp) M.delay(println("too many retries, giving up")) else M.pure()
    )(attempt(prompt))
  }

  private def attempt(prompt: String): F[Option[Event]] = {
    for {
      gptResponseStrOpt <- manager
        .ask(prompt, config.gptCount, parseGptResponse(_).nonEmpty)
        .value
        .handleErrorWith(er => M.delay(println("ask throw exception: " + er)) *> M.pure(None))
      event = for {
        gptResponseStr <- gptResponseStrOpt
        event          <- parseGptResponse(gptResponseStr)
      } yield event
      res <- event match {
        case None    => M.delay(println("unsuccessful attempt")) *> M.pure(None)
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
