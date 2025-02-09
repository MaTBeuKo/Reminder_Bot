package reminder.api

import cats.data.OptionT
import cats.effect.IO

trait GptProvider {

  def ask(text: String): OptionT[IO, String]
  def ask(text: String, count: Int, ensure: String => IO[Boolean]): OptionT[IO, String]

}
