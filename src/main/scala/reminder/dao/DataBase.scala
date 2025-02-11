package reminder.dao

import cats.effect.Async
import cats.implicits._
import doobie.implicits._
import doobie.{Transactor, Write}

import scala.language.higherKinds

case class DBConfig(dbDriver: String, dbUrl: String, dbUser: String, dbPassword: String)

class DataBase[F[_]](config: DBConfig)(implicit M : Async[F]) {

  val xa = Transactor.fromDriverManager[F](
    driver = config.dbDriver,
    url = config.dbUrl,
    user = config.dbUser,
    password = config.dbPassword,
    logHandler = None
  )

  implicit val eventWriter: Write[DBEvent] =
    Write[(Long, Long, String)].contramap(e => (e.userId, e.time, e.topic))

  def updateTime(userId: Long, time: Long): F[Unit] =
    sql"""
         INSERT INTO users (user_id, last_msg_epoch)
         VALUES ($userId, $time) ON CONFLICT (user_id)
         DO UPDATE
         SET last_msg_epoch = $time
    """.update.run.transact(xa).void

  def getTime(userId: Long): F[Option[Long]] =
    sql"""
    SELECT last_msg_epoch FROM users
    WHERE user_id = $userId
   """.query[Option[Long]].option.transact(xa).map(_.flatten)

  def updateTimezone(userId: Long, offset: Long): F[Unit] =
    sql"""
         INSERT INTO users (user_id, timezone_offset)
         VALUES ($userId, $offset) ON CONFLICT (user_id)
         DO UPDATE
         SET timezone_offset = $offset
    """.update.run.transact(xa).void

  def getTimezone(userId: Long): F[Option[Int]] =
    sql"""
    SELECT timezone_offset FROM users
    WHERE user_id = $userId
   """.query[Option[Int]].option.map(_.flatten).transact(xa)

  def addEvent(event: DBEvent): F[Unit] =
    sql"""
    INSERT INTO events (user_id, event_epoch, topic)
    VALUES ($event)
  """.update.run.transact(xa).void

  def deleteEarliestEvent(): F[Unit] = {
    sql"""
  DELETE FROM events
  WHERE message_id = (
    SELECT message_id
    FROM events
    ORDER BY event_epoch ASC
    LIMIT 1
  )
""".update.run.transact(xa).void
  }

  def deleteLastAddedEvent(userId: Long): F[Option[String]] = {
    for {
      event <- sql"""
         SELECT message_id, topic FROM events
         WHERE user_id = $userId
         ORDER BY message_id DESC 
         LIMIT 1
       """.query[(Long, String)].option.transact(xa)
      topicOpt <- event match {
        case Some(event) =>
          for {
            _ <- sql"""
               DELETE FROM events
               WHERE message_id = ${event._1}
             """.update.run.transact(xa)
            result <- M.pure(Some(event._2))
          } yield result
        case None => M.pure(None)
      }
    } yield topicOpt
  }

  def deleteEventByMessageId(messageId: Long): F[Unit] =
    for {
      _ <- sql"""
               DELETE FROM events
               WHERE message_id = $messageId
             """.update.run.transact(xa)
    } yield ()

  def deleteEventByName(userId: Long, name: String): F[Option[Unit]] = {
    for {
      messageId <- sql"""
         SELECT message_id FROM events
         WHERE user_id = $userId AND topic = $name
         ORDER BY message_id DESC 
         LIMIT 1
       """.query[Long].option.transact(xa)
      res <- messageId match {
        case Some(id) => deleteEventByMessageId(id) *> M.pure(Some())
        case None     => M.pure(None)
      }
    } yield res
  }

  def getEarliestEvent(): F[Option[DBEvent]] =
    sql"""
    SELECT message_id, user_id, event_epoch, topic FROM events
    ORDER BY event_epoch ASC
    LIMIT 1
  """.query[DBEvent]
      .option
      .transact(xa)

}

object DataBase {

  def apply[F[_]](config: DBConfig)(implicit M : Async[F]): F[DataBase[F]] =
    M.delay(new DataBase[F](config)(M))

}
