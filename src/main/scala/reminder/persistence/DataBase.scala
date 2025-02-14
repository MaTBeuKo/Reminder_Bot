package reminder.persistence

import scala.language.higherKinds

trait DataBase[F[_]] {

  def updateTime(userId: Long, time: Long): F[Unit]
  def getTime(userId: Long): F[Option[Long]]
  def updateTimezone(userId: Long, offset: Long): F[Unit]
  def getTimezone(userId: Long): F[Option[Int]]
  def addEvent(event: DBEvent): F[Unit]
  def deleteEarliestEvent(): F[Unit]
  def deleteLastAddedEvent(userId: Long): F[Option[String]]
  def deleteEventByMessageId(messageId: Long): F[Unit]
  def deleteEventByName(userId: Long, name: String): F[Option[Unit]]
  def getEarliestEvent: F[Option[DBEvent]]

}
