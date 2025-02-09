package reminder.notifier

import akka.http.scaladsl.model.DateTime
import cats.effect.IO
import reminder.bot.TGBot
import reminder.dao.DataBase

import scala.concurrent.duration.{Duration, SECONDS}

object Notifier {

  def run(bot: TGBot, db: DataBase): IO[Unit] = {
    (for {
      eventOpt <- db.getEarliestEvent()
      _ <- eventOpt match {
        case Some(event) if DateTime(event.time) <= DateTime.now =>
          bot.sendMessage(event.userId, event.topic) *> db.deleteEarliestEvent()
        case _ =>
          IO.sleep(Duration(1, SECONDS))
      }
      _ <- run(bot, db)
    } yield ()).handleError(e => IO.println("error in notifier: " + e))
  }

}
