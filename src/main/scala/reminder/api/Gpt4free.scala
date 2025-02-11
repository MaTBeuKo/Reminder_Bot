package reminder.api

import cats.data.OptionT
import cats.effect.implicits.genTemporalOps
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.std.Random
import cats.effect.{Async, Spawn}
import cats.implicits._
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import sttp.client3.circe.asJson
import sttp.client3.{Request, SttpBackend, basicRequest}
import sttp.model.Uri

import scala.annotation.meta.param
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.language.higherKinds

case class GPTConfig(private val gptRequestTimeout: Int, gffEndpoint: String) {
  def timeout: FiniteDuration = FiniteDuration(gptRequestTimeout, SECONDS)
}

class Gpt4free[F[+_]] private (backend: SttpBackend[F, Any], config: GPTConfig)(implicit
  M: Async[F]
) extends GptProvider {

  private val random              = Random.scalaUtilRandom[F]
  private val providersEndpoint   = Uri.unsafeParse(config.gffEndpoint).addPath("providers")
  private val completionsEndpoint = Uri.unsafeParse(config.gffEndpoint).addPath("chat/completions")

  def ask(text: String): OptionT[F, String] =
    ask(text, 1, _ => true)

  private def raceFirstSome[G[+_], A](a: G[Option[A]], b: G[Option[A]])(implicit
    S: Spawn[G]
  ): G[Option[A]] = {
    (for {
      race <- S.racePair(a, b)
      resAndFib = race match {
        case Left((Succeeded(a), fibB))  => (a, fibB)
        case Left((_, fibB))             => (S.pure(None), fibB)
        case Right((fibA, Succeeded(b))) => (b, fibA)
        case Right((fibA, _))            => (S.pure(None), fibA)
      }
      finalOpt = for {
        opt <- resAndFib._1
        fib = resAndFib._2
      } yield opt match {
        case Some(a) => fib.cancel *> S.pure(Some(a))
        case _       => fib.joinWith(S.pure(None))
      }
    } yield finalOpt.flatten).flatten
  }

  /** Sends [[param text]] request to [[param count]] GPTs at the same time and waits for at most
    * [[param timeout]] time for any of them to answer. Returns None if it didn't get any response
    * or the first response otherwise Useful when you want to generate a reply asap, with a cost of
    * making too many requests.
    */
  def ask(text: String, count: Int, ensure: String => Boolean): OptionT[F, String] = {
    val res = for {
      providersList <- providers
      batches       <- M.delay(providersList.grouped(count).toVector)
      rand          <- random
      batchId       <- rand.nextIntBounded(batches.size)
      batch         <- M.delay(batches(batchId))
      _ <- M.delay(println("Using following providers for request : " + batch.mkString(", ")))
      responses <- M.delay(batch.map(getGptResponse(text, _, config.timeout)))
      responses <- M.delay(responses.map(opt => opt.filter(ensure)))

      race <- responses
        .map(_.value)
        .reduce((x, y) => raceFirstSome(x, y))
        .timeout(config.timeout)
        .map(Option(_))
        .handleError(_ => None)
        .map(_.flatten)

    } yield race
    OptionT(res)
  }

  private val providers: F[List[String]] = getProviders.getOrElse(List[String]())

  private def postRequest[E, R](
    request: Request[Either[E, R], Any]
  ): F[Option[R]] =
    for {
      responseOpt <- backend.send(request).map(Option(_)).handleError(_ => None)
      res <- responseOpt.map(_.body) match {
        case Some(exOrRes) =>
          exOrRes match {
            case Right(res) => M.delay(Some(res))
            case Left(ex) =>
              M.delay(println("error while sending post request: " + ex.toString)) *> M.pure(None)
          }
        case None =>
          M.delay(println("error while sending post request, no response")) *> M.pure(None)
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
  ): OptionT[F, String] = {

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
    val res = for {
      response <- postRequest(request)
    } yield response.flatMap(_.choices.headOption.map(_.message.content))
    OptionT(res)
  }

  private def getProviders: OptionT[F, List[String]] = {
    case class Provider(id: String)
    val request = basicRequest
      .get(providersEndpoint)
      .header("Accept", "application/json")
      .response(asJson[List[Provider]])
    val res = for {
      providers <- postRequest(request)
      result <- providers match {
        case Some(list) => M.delay(Some(list.map(_.id)))
        case None       => M.delay(println("Couldn't load providers")) *> M.pure(None)
      }
    } yield result
    OptionT(res)
  }

}

object Gpt4free {

  def apply[F[+_]](backend: SttpBackend[F, Any], config: GPTConfig)(implicit
    M: Async[F]
  ): Gpt4free[F] =
    new Gpt4free[F](backend, config)(M)

}
