package reminder.bot

import akka.http.scaladsl.model.DateTime
import cats.effect.IO
import io.circe.parser
import reminder.api.GptProvider
import reminder.notifier.Event

class EventMaker(manager: GptProvider, config: BotConfig) {

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

  def attemptN(prompt: String, count: Int): IO[Option[Event]] = {
    import retry._
    retryingOnFailures[Option[Event]](
      RetryPolicies.limitRetries[IO](count),
      opt => IO(opt.nonEmpty),
      (_, y) => if (y.givingUp) IO.println("too many retries, giving up") else IO()
    )(attempt(prompt))
  }

  private def attempt(prompt: String): IO[Option[Event]] = {
    for {
      gptResponseStrOpt <- manager
        .ask(prompt, config.gptCount, parseGptResponse(_).nonEmpty)
        .value
        .handleErrorWith(er => IO.println("ask throw exception: " + er) *> IO(None))
      event = for {
        gptResponseStr <- gptResponseStrOpt
        event          <- parseGptResponse(gptResponseStr)
      } yield event
      res <- event match {
        case None    => IO.println("unsuccessful attempt") *> IO(None)
        case Some(_) => IO.pure(event)
      }
    } yield res
  }

  private def parseGptResponse(text: String): Option[Event] =
    for {
      json  <- parser.parse(text).toOption
      event <- Event.fromJson(json)
    } yield event

}
