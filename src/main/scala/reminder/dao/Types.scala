package reminder.dao

case class DBEvent(eventId: Long, userId: Long, time: Long, topic: String)
