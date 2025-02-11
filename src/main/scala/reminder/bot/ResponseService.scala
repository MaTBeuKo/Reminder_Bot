package reminder.bot

import canoe.models.messages.{LocationMessage, TextMessage}
import cats.effect.kernel.Async

import scala.language.higherKinds

abstract class ResponseService[F[_]: Async] {

  def toNewEvent(msg: TextMessage): F[Unit]

  def toLocation(msg: LocationMessage): F[Unit]

  def toPop(msg: TextMessage): F[Unit]

  def toRemove(msg: TextMessage): F[Unit]

  def toStart(msg: TextMessage): F[Unit]

  def handleFatal(chatId: Long, er: Throwable): F[Unit]

}
