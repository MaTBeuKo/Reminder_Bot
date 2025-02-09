package reminder.bot

import canoe.api.TelegramClient
import canoe.models.messages.{LocationMessage, TelegramMessage, TextMessage}
import cats.effect.IO

trait ResponseService {

  def toNewEvent(msg: TextMessage): IO[Unit]

  def toLocation(msg: LocationMessage): IO[Unit]

  def toPop(msg: TextMessage): IO[Unit]

  def toRemove(msg: TextMessage): IO[Unit]

  def toStart(msg: TextMessage): IO[Unit]

  def toRu(msg: TextMessage): IO[Unit]
  def toEn(msg: TextMessage): IO[Unit]
  def handleFatal(chatId: Long, er: Throwable): IO[Unit]

}
