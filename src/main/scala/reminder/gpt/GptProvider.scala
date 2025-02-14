package reminder.gpt

import cats.data.OptionT

import scala.language.higherKinds

trait GptProvider[F[_]] {

  def ask(text: String): OptionT[F, String]
  def ask(text: String, count: Int, ensure: String => Boolean): OptionT[F, String]

}
