package reminder.api

import cats.MonadThrow
import cats.data.OptionT
import cats.implicits._
import io.circe.generic.auto._
import io.circe.syntax.EncoderOps
import org.typelevel.log4cats.LoggerFactory
import reminder.gpt.GPTConfig
import sttp.client3.circe.asJson
import sttp.client3.{Request, SttpBackend, basicRequest}
import sttp.model.Uri

import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds

trait Gpt4freeApi[F[_]] {

  def getProviders: OptionT[F, List[String]]

  def getGptResponse(
    text: String,
    provider: String,
    requestTimeout: FiniteDuration
  ): OptionT[F, String]

}

object Gpt4freeApi {

  def apply[F[+_]: LoggerFactory](backend: SttpBackend[F, Any], config: GPTConfig)(implicit
    M: MonadThrow[F]
  ): Gpt4freeApi[F] = new Gpt4freeApi[F] {
    private val providersEndpoint = Uri.unsafeParse(config.gffEndpoint).addPath("providers")
    private val completionsEndpoint =
      Uri.unsafeParse(config.gffEndpoint).addPath("chat/completions")
    private val log = LoggerFactory[F].getLogger

    private def postRequest[E, R](
      request: Request[Either[E, R], Any]
    ): F[Option[R]] =
      for {
        responseOpt <- backend.send(request).map(Option(_)).handleError(_ => None)
        res <- responseOpt.map(_.body) match {
          case Some(exOrRes) =>
            exOrRes match {
              case Right(res) => M.pure(Some(res))
              case Left(ex) =>
                log.info("error while sending post request: " + ex.toString) *> M.pure(None)
            }
          case None =>
            log.info("error while sending post request, no response") *> M.pure(None)
        }
      } yield res

    private case class Message(role: String, content: String)
    private case class ChatRequest(messages: List[Message], provider: String)
    private case class Choice(message: Msg)
    private case class ResponseData(choices: List[Choice])
    private case class Msg(content: String)
    private case class Provider(id: String)

    override def getProviders: OptionT[F, List[String]] = {
      val request = basicRequest
        .get(providersEndpoint)
        .header("Accept", "application/json")
        .response(asJson[List[Provider]])
      val res = for {
        providers <- postRequest(request)
        result <- providers match {
          case Some(list) => M.pure(Some(list.map(_.id)))
          case None       => log.error("Couldn't load providers") *> M.pure(None)
        }
      } yield result
      OptionT(res)
    }

    override def getGptResponse(
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
  }

}
