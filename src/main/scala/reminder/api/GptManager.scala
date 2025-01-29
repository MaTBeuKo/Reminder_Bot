package reminder.api

import cats.effect.IO
import cats.effect.std.Random
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import sttp.client3.circe.asJson
import sttp.client3.{Identity, RequestT, SttpBackend, basicRequest}
import sttp.model.Uri

import scala.annotation.meta.param
import scala.concurrent.duration.{FiniteDuration, SECONDS}

case class GPTConfig(private val gptRequestTimeout: Int, gffEndpoint: String) {
  def timeout: FiniteDuration = FiniteDuration(gptRequestTimeout, SECONDS)
}

/** Produces no side effects on creation
  */
class GptManager(backend: SttpBackend[IO, Any], config: GPTConfig) {

  private val random              = Random.scalaUtilRandom[IO]
  private val providersEndpoint   = Uri.unsafeParse(config.gffEndpoint).addPath("providers")
  private val completionsEndpoint = Uri.unsafeParse(config.gffEndpoint).addPath("chat/completions")

  /** Sends [[param text]] request to [[param count]] GPTs at the same time and waits for at most
    * [[param timeout]] time for any of them to answer. Returns None if it didn't get any response
    * or the first response otherwise Useful when you want to generate a reply asap, with a cost of
    * making too many requests.
    */
  def ask(text: String, count: Int, ensure: String => IO[Boolean]): IO[Option[String]] = {
    for {
      providersList <- providers
      batches       <- IO(providersList.grouped(count).toVector)
      rand          <- random
      batchId       <- rand.nextIntBounded(batches.size)
      batch         <- IO(batches(batchId))
      _             <- IO.println("Using following providers for request : " + batch.mkString(", "))

      responses <- IO(batch.map(getGptResponse(text, _, config.timeout).flatMap {
        case None => IO(None).andWait(config.timeout)
        case Some(x) =>
          (for {
            isGood <- ensure(x)
          } yield if (isGood) IO(Some(x)) else IO(None).andWait(config.timeout)).flatten
      }))

      race <- responses
        .reduce((x, y) => x.race(y).map(_.merge))
        .timeout(config.timeout)
        .option
        .map(_.flatten)
    } yield race
  }

  private val providers: IO[List[String]] = getProviders.map(res => res.getOrElse(List[String]()))

  private def postRequest[E, R](
    request: RequestT[Identity, Either[E, R], Any]
  ): IO[Option[R]] =
    for {
      responseOpt <- backend.send(request).option
      res <- responseOpt.map(_.body) match {
        case Some(exOrRes) =>
          exOrRes match {
            case Right(res) => IO(Some(res))
            case Left(ex) =>
              IO.println("error while sending post request: " + ex.toString) *> IO(None)
          }
        case None => IO.println("error while sending post request, no response") *> IO(None)
      }
    } yield res

  private case class Message(role: String, content: String)
  private case class ChatRequest(messages: List[Message], provider: String)
  private case class ResponseData(choices: List[Choice])
  private case class Choice(message: Msg)
  private case class Msg(content: String)

  private def getGptResponse(
    text: String,
    provider: String,
    requestTimeout: FiniteDuration
  ): IO[Option[String]] = {

    val chatRequest = ChatRequest(
      messages = List(Message("user", text)),
      provider = provider
    )
    val request = basicRequest
      .post(completionsEndpoint)
      .contentType("application/json")
      .body(chatRequest.asJson.noSpaces)
      .readTimeout(requestTimeout)
      .response(asJson[ResponseData])
    for {
      response <- postRequest(request)
    } yield response.flatMap(_.choices.headOption.map(_.message.content))
  }

  private def getProviders: IO[Option[List[String]]] = {
    case class Provider(id: String)
    val request = basicRequest
      .get(providersEndpoint)
      .header("Accept", "application/json")
      .response(asJson[List[Provider]])
    for {
      providers <- postRequest(request)
      result <- providers match {
        case Some(list) => IO(Some(list.map(_.id)))
        case None       => IO.println("Couldn't load providers") *> IO(None)
      }
    } yield result
  }

}
