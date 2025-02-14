package reminder.gpt

import cats.data.OptionT
import cats.effect.implicits.genTemporalOps
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.std.Random
import cats.effect.{Async, Spawn}
import cats.implicits._
import org.typelevel.log4cats.LoggerFactory
import reminder.api.Gpt4freeApi

import scala.annotation.meta.param
import scala.concurrent.duration.{FiniteDuration, SECONDS}
import scala.language.higherKinds

case class GPTConfig(private val gptRequestTimeout: Int, gffEndpoint: String) {
  def timeout: FiniteDuration = FiniteDuration(gptRequestTimeout, SECONDS)
}

class Gpt4free[F[+_]: LoggerFactory] private (api: Gpt4freeApi[F], config: GPTConfig)(
  implicit
  M: Async[F]
) extends GptProvider[F] {

  private val random              = Random.scalaUtilRandom[F]
  private val log                 = LoggerFactory[F].getLogger

  def ask(text: String): OptionT[F, String] =
    ask(text, 1, _ => true)

  /** Sends [[param text]] request to [[param count]] GPTs at the same time and waits for at most
    * [[param timeout]] time for any of them to answer. Returns None if it didn't get any response
    * or the first response otherwise Useful when you want to generate a reply asap, with a cost of
    * making too many requests.
    */
  def ask(text: String, count: Int, ensure: String => Boolean): OptionT[F, String] = {
    val res = for {
      providersList <- api.getProviders.getOrElse(List[String]())
      batches       <- M.pure(providersList.grouped(count).toVector)
      rand          <- random
      batchId       <- rand.nextIntBounded(batches.size)
      batch         <- M.pure(batches(batchId))
      _             <- log.info("Using following providers for request : " + batch.mkString(", "))
      responses     <- M.delay(batch.map(api.getGptResponse(text, _, config.timeout)))
      responses     <- M.delay(responses.map(opt => opt.filter(ensure)))

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

  /** Starts a race between two computations which results may be absent and yields the first one
    * that ended with a result or None if both computations didn't produce a result (if first
    * computations that finished produces None, it will wait for the second computation to finish)
    */
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

}

object Gpt4free {

  def apply[F[+_]](api: Gpt4freeApi[F], config: GPTConfig)(implicit
    M: Async[F],
    L: LoggerFactory[F]
  ): Gpt4free[F] =
    new Gpt4free[F](api, config)(L, M)

}
