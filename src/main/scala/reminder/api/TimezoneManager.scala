package reminder.api

import canoe.models.Location
import cats.effect.Async
import cats.implicits._
import io.circe.generic.auto._
import sttp.client3.{SttpBackend, _}
import sttp.client3.circe._

import scala.language.higherKinds

class TimezoneManager[F[_]](api: String, backend: SttpBackend[F, Any])(implicit
  M: Async[F]
) {

  case class TimezoneData(utcOffsetSeconds: Int, ianaTimeId: String)

  def getTimezone(loc: Location): F[TimezoneData] = {
    val queryParams = Map(
      "latitude"  -> loc.latitude,
      "longitude" -> loc.longitude,
      "key"       -> api
    )

    val request = basicRequest
      .get(uri"https://api-bdc.net/data/timezone-by-location?$queryParams")
      .header("Accept", "application/json")
      .response(asJson[TimezoneData])
    for {
      response <- backend.send(request)
    } yield response.body.getOrElse(TimezoneData(0, "UTC"))
  }

}
