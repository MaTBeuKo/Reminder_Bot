package reminder.notifier

import akka.http.scaladsl.model.DateTime
import cats.effect.{Async, IO}
import reminder.bot.TGBot
import reminder.dao.DataBase
import cats.implicits._

import scala.concurrent.duration.{Duration, SECONDS}
import scala.language.higherKinds

object Notifier {

  def run[F[+_]](bot: TGBot[F], db: DataBase[F])(implicit
    M: Async[F]
  ): F[Unit] = {
    (for {
      eventOpt <- db.getEarliestEvent()
      _ <- eventOpt match {
        case Some(event) if DateTime(event.time) <= DateTime.now =>
          bot.sendMessage(event.userId, event.topic) *> db.deleteEarliestEvent()
        case _ =>
          M.sleep(Duration(1, SECONDS))
      }
      _ <- run(bot, db)
    } yield ()).handleError(e => IO.println("error in notifier: " + e))
  }

}
