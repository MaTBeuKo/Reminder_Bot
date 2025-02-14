package reminder.persistence

case class DBEvent(eventId: Long, userId: Long, time: Long, topic: String)
