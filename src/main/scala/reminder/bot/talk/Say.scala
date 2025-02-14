package reminder.bot.talk

import reminder.notifier.Event

import scala.concurrent.duration.FiniteDuration

trait Say {

  def handlingEvent: String
  def badEvent: String
  def eventPlanned(event: Event, zone: Int): String
  def notSoFast(duration: FiniteDuration): String
  def timezoneSet(zone: String): String
  def eventRemoved(topic: String): String
  def noEvents: String
  def provideTopic: String
  def noSuchEvent: String
  def start(zone: Int): String
  def fatalError: String

}
