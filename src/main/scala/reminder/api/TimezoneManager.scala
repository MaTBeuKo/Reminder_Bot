package reminder.api

import canoe.models.Location
import cats.effect.IO
import sttp.client3.SttpBackend

class TimezoneManager(api: String, backend: SttpBackend[IO, Any]) {
  case class TimezoneData(utcOffsetSeconds: Int, ianaTimeId: String)

  def getTimezone(loc: Location): IO[TimezoneData] = {
    val queryParams = Map(
      "latitude"  -> loc.latitude,
      "longitude" -> loc.longitude,
      "key"       -> api
    )

    import io.circe.generic.auto._
    import sttp.client3._
    import sttp.client3.circe._
    val request = basicRequest
      .get(uri"https://api-bdc.net/data/timezone-by-location?$queryParams")
      .header("Accept", "application/json")
      .response(asJson[TimezoneData])
    for {
      response <- backend.send(request)
    } yield response.body.getOrElse(TimezoneData(0, "UTC"))
  }
}
