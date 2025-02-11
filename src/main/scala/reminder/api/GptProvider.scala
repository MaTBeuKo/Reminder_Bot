package reminder.api

import cats.data.OptionT
import cats.effect.Async

import scala.language.higherKinds

abstract class GptProvider[F[+_] : Async] {

  def ask(text: String): OptionT[F, String]
  def ask(text: String, count: Int, ensure: String => Boolean): OptionT[F, String]

}
