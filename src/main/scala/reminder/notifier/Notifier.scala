package reminder.notifier

import akka.http.scaladsl.model.DateTime
import cats.effect.Temporal
import cats.implicits._
import reminder.bot.Send
import reminder.persistence.DataBase

import scala.concurrent.duration.{Duration, SECONDS}
import scala.language.higherKinds

object Notifier {

  def run[F[_]](send: Send[F], db: DataBase[F])(implicit
    M: Temporal[F]
  ): F[Unit] = {
    for {
      eventOpt <- db.getEarliestEvent
      _ <- eventOpt match {
        case Some(event) if DateTime(event.time) <= DateTime.now =>
          send.sendMessage(event.userId, event.topic) *> db.deleteEarliestEvent()
        case _ =>
          M.sleep(Duration(1, SECONDS))
      }
      _ <- run(send, db)
    } yield ()
  }

}
