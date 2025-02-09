package reminder.bot.talk

import reminder.notifier.Event
import reminder.syntax.Pretty.DateTimeOps

import scala.concurrent.duration.FiniteDuration

class SayInEnglish extends Say {

  override def handlingEvent: String = "Handling your request... be patient"

  override def badEvent: String =
    "Couldn't parse this event, make sure there is a date specified and try again."

  override def eventPlanned(event: Event, zone: Int): String =
    s"${event.topic} planned at ${event.time.weekdayWithTimezone(zone)}"

  override def notSoFast(duration: FiniteDuration): String =
    s"Not so fast! Try again in ${duration.toSeconds} seconds"

  override def timezoneSet(zone: String): String = "Your timezone is set to: " + zone

  override def eventRemoved(topic: String): String = "Removed " + topic

  override def noEvents: String = "You don't have any events planned"

  override def provideTopic: String = "You didn't provide topic name"

  override def noSuchEvent: String = "This event doesn't exist"

  override def start: String = "Hi!\n" +
    "I can remind you about any event, just send me description and time of it (possibly with time zone)\n" +
    "To start send me your location (any location in your time zone) or UTC will be used by default"

  override def fatalError: String = "Service unavailable, try again later..."
}
