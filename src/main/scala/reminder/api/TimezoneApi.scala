package reminder.api

import canoe.models.Location
import cats.Functor
import cats.implicits._
import io.circe.generic.auto._
import sttp.client3.circe._
import sttp.client3.{SttpBackend, _}

import scala.language.higherKinds

case class TimezoneData(utcOffsetSeconds: Int, ianaTimeId: String)

trait TimezoneApi[F[_]] {

  def getTimezone(loc: Location): F[TimezoneData]

}

object TimezoneApi {

  def apply[F[_]: Functor](api: String, backend: SttpBackend[F, Any]): TimezoneApi[F] = {
    (loc: Location) => {
      val queryParams = Map(
        "latitude" -> loc.latitude,
        "longitude" -> loc.longitude,
        "key" -> api
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

}
