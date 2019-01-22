package org.mauritania.main4ino.api.v1

import java.time.{ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter

import cats.Monad
import io.circe.syntax._
import org.http4s.circe._
import io.circe.generic.auto._
import org.http4s.{AuthedService, Cookie, HttpService, MediaType, Request, Response}
import org.mauritania.main4ino.{Repository, RepositoryIO}
import org.mauritania.main4ino.models._
import org.http4s.headers.`Content-Type`
import org.mauritania.main4ino.RepositoryIO.Table.Table
import org.mauritania.main4ino.api.v1.ActorMapV1.ActorMapV1
import org.mauritania.main4ino.api.v1.DeviceV1.MetadataV1
import org.mauritania.main4ino.api.v1.PropsMapV1.PropsMapV1
import org.mauritania.main4ino.helpers.Time
import cats.data.{Kleisli, OptionT}
import cats.effect.Sync
import cats.implicits._
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.dsl.Http4sDsl
import org.http4s.server.AuthMiddleware
import org.mauritania.main4ino.security.Authentication.AccessAttempt
import org.mauritania.main4ino.security.{Authentication, User}

import scala.util.{Failure, Success, Try}

class Service[F[_]: Sync](auth: Authentication[F], repository: Repository[F], time: Time[F]) extends Http4sDsl[F] {

  import Service._
  import Url._

  private val HelpMsg =
    s"""
       | API HELP
       | --- ----
       |
       | HELP
       | ----
       |
       | GET /help
       |
       |    Display this help
       |
       |    Returns: OK (200)
       |
       |
       | TIME
       | ----
       |
       | GET /time?timezone=<tz>
       |
       |    Return the formatted time at a given timezone (example: UTC, Europe/Paris, etc.)
       |
       |    Returns: OK (200) | BAD_REQUEST (400)
       |
       |
       | USER
       | ----
       |
       | POST /session (with standard basic auth)
       |
       |    Return the session id
       |
       |    Returns: OK (200)
       |
       |
       | GET /user
       |
       |    Return the user id (if logged in)
       |
       |    Returns: OK (200)
       |
       |
       | DEVICES
       | -------
       |
       | POST /devices/<dev>/targets/
       |
       |    Create a target
       |
       |    Returns: CREATED (201)
       |
       |
       | GET /devices/<dev>/targets/<id>
       |
       |    Retrieve a target by its id
       |
       |    Returns: OK (200) | NO_CONTENT (204)
       |
       |
       | GET /devices/<dev>/targets/last
       |
       |    Retrieve the last target created
       |
       |    Returns: OK (200) | NO_CONTENT (204)
       |
       |
       | GET /devices/<dev>/targets?from=<timestamp>&to=<timestamp>
       |
       |    Retrieve the list of the targets that where created in between the range provided (timestamp in [ms] since the epoch)
       |
       |    Returns: OK (200)
       |
       |
       | GET /devices/<dev>/targets/summary?status=<status>&consume=<consume>
       |
       |    Retrieve the list of the targets summarized for the device (most recent actor-prop value wins)
       |
       |    The summarized target is generated only using properties that have the given status.
       |    The flag consume tells if the status of the matching properties should be changed from C (created) to X (consumed).
       |
       |    Returns: OK (200) | NO_CONTENT (204)
       |
       |
       | GET /devices/<dev>/targets/count?status=<status>
       |
       |    Count the amount of target-properties with the given status for the device
       |
       |    Returns: OK (200)
       |
       |
       | POST /devices/<dev>/actors/<actor>/targets
       |
       |    Create a new target
       |
       |    Returns: CREATED (201)
       |
       |
       | GET /devices/<dev>/actors/<actor>/targets/count?status=<status>
       |
       |    Count the amount of target-properties with the given status
       |
       |    Returns: OK (200)
       |
       |
       | GET /devices/<dev>/actors/<actor>/targets?status=<status>&consume=<consume>
       |
       |    Retrieve the list of the targets for the device-actor (most recent actor-prop value wins)
       |
       |    The list is generated only using properties that have the given status.
       |    The flag consume tells if the status of the matching properties should be changed from C (created) to X (consumed).
       |
       |    Returns: OK (200)
       |
       |
       | GET /devices/<dev>/actors/<actor>/targets/summary?status=<status>&consume=<consume>
       |
       |    Retrieve the summary of the targets for the device-actor (most recent actor-prop value wins)
       |
       |    The summarized target is generated only using properties that have the given status.
       |    The flag consume tells if the status of the matching properties should be changed from C (created) to X (consumed).
       |
       |    Returns: OK (200) | NO_CONTENT (204)
       |
       |
       | GET /devices/<dev>/actors/<actor>/targets/last?status=<status>
       |
       |    Retrieve the last target created for such actor with such status
       |
       |    Returns: OK (200) | NO_CONTENT (204)
       |
       |
    """.stripMargin

  private[v1] val service = AuthedService[User, F] {

      // Help

      case GET -> _ / "help" as _ =>
        Ok(HelpMsg, ContentTypeTextPlain)

      // Date/Time

      case GET -> _ / "time" :? TimezoneParam(tz) as _ => {
        val attempt = Try(nowAtTimezone(tz.getOrElse("UTC")))
        attempt match {
          case Success(v) => Ok(v.map(_.asJson), ContentTypeTextPlain)
          case Failure(f) => BadRequest()
        }
      }

      // Targets & Reports (at device level)

      case a@POST -> _ / "devices" / Dvc(device) / Tbl(table) as _ => {
        val x = postDev(a.req, device, table, time.nowUtc)
        Created(x.map(_.asJson), ContentTypeAppJson)
      }

      case a@GET -> _ / "devices" / Dvc(device) / Tbl(table) / LongVar(id) as _ => {
        val x = getDev(table, id)
        x.flatMap {
          case Some(v) => Ok(v.asJson, ContentTypeAppJson)
          case None => NoContent()
        }
      }

      case a@GET -> _ / "devices" / Dvc(device) / Tbl(table) / "last" as _ => {
        val x = getDevLast(device, table)
        x.flatMap {
          case Some(v) => Ok(v.asJson, ContentTypeAppJson)
          case None => NoContent()
        }
      }

      case a@GET -> _ / "devices" / Dvc(device) / Tbl(table) :? FromParam(from) +& ToParam(to) as _ => {
        val x = getDevAll(device, table, from, to)
        Ok(x.map(_.asJson.noSpaces), ContentTypeAppJson)
      }

      case a@GET -> _ / "devices" / Dvc(device) / Tbl(table) / "summary" :? StatusParam(status) +& ConsumeParam(consume) as _ => {
        val x = getDevActorTups(device, None, table, status, consume).map(t => ActorMapV1.fromTups(t))
        x.flatMap { m =>
          if (m.isEmpty) {
            NoContent()
          } else {
            Ok(m.asJson, ContentTypeAppJson)
          }
        }
      }

      case a@GET -> _ / "devices" / Dvc(device) / Tbl(table) / "count" :? StatusParam(status) as _ => {
        val x = getDevActorCount(device, None, table, status)
        Ok(x.map(_.asJson), ContentTypeAppJson)
      }

      // Targets & Reports (at device-actor level)

      case a@POST -> _ / "devices" / Dvc(device) / "actors" / Dvc(actor) / Tbl(table) as _ => {
        val x = postDevActor(a.req, device, actor, table, time.nowUtc)
        Created(x.map(_.asJson), ContentTypeAppJson)
      }

      case a@GET -> _ / "devices" / Dvc(device) / "actors" / Dvc(actor) / Tbl(table) / "count" :? StatusParam(status) as _ => {
        val x = getDevActorCount(device, Some(actor), table, status)
        Ok(x.map(_.asJson), ContentTypeAppJson)
      }

      case a@GET -> _ / "devices" / Dvc(device) / "actors" / Dvc(actor) / Tbl(table) :? StatusParam(status) +& ConsumeParam(consume) as _ => {
        val x = getDevActors(device, actor, table, status, consume)
        Ok(x.map(_.asJson), ContentTypeAppJson)
      }

      case a@GET -> _ / "devices" / Dvc(device) / "actors" / Dvc(actor) / Tbl(table) / "summary" :? StatusParam(status) +& ConsumeParam(consume) as _ => {
        val x = getDevActorTups(device, Some(actor), table, status, consume).map(PropsMapV1.fromTups)
        x.flatMap { m =>
          if (m.isEmpty) {
            NoContent()
          } else {
            Ok(m.asJson, ContentTypeAppJson)
          }
        }
      }

      case a@GET -> _ / "devices" / Dvc(device) / "actors" / Dvc(actor) / Tbl(table) / "last" :? StatusParam(status) as _ => {
        val x = getLastDevActorTups(device, actor, table, status).map(PropsMapV1.fromTups)
        x.flatMap { m =>
          if (m.isEmpty) {
            NoContent()
          } else {
            Ok(m.asJson, ContentTypeAppJson)
          }
        }
      }

      case a@POST -> _ / "session" as user => {
        val session = auth.generateSession(user)
        session.flatMap(s => Ok(s))
      }

      case a@GET -> _ / "user" as user => {
        Ok(user.name)
      }

    }

  private[v1] def getDev(table: Table, id: RecordId): F[Option[DeviceV1]] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      device <- repository.selectDeviceWhereRequestId(table, id)
      deviceU = device.map(DeviceV1.fromBom)
      _ <- logger.debug(s"GET device $id from table $table: $deviceU")
    } yield (deviceU)
  }

  private[v1] def postDev(req: Request[F], device: DeviceName, table: Table, dt: F[ZonedDateTime]): F[IdResponse] = {
    implicit val x = JsonEncoding.StringDecoder
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      t <- dt
      ts = Time.asTimestamp(t)
      actorMapU <- req.decodeJson[ActorMapV1]
      deviceBom = DeviceV1(MetadataV1(None, Some(ts), device), actorMapU).toBom
      id <- repository.insertDevice(table, deviceBom)
      _ <- logger.debug(s"POST device $device into table $table: $deviceBom / $id")
      resp = IdResponse(id)
    } yield (resp)
  }

  private[v1] def postDevActor(req: Request[F], device: DeviceName, actor: ActorName, table: Table, dt: F[ZonedDateTime]): F[IdResponse] = {
    implicit val x = JsonEncoding.StringDecoder
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      t <- dt
      ts = Time.asTimestamp(t)
      p <- req.decodeJson[PropsMapV1]
      deviceBom = DeviceV1(MetadataV1(None, Some(ts), device), Map(actor -> p)).toBom
      id <- repository.insertDevice(table, deviceBom)
      _ <- logger.debug(s"POST device $device (actor $actor) into table $table: $deviceBom / $id")
      resp = IdResponse(id)
    } yield (resp)
  }

  private[v1] def getDevLast(device: DeviceName, table: Table): F[Option[DeviceV1]] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      r <- repository.selectMaxDevice(table, device)
      deviceBom = r.map(DeviceV1.fromBom)
      _ <- logger.debug(s"GET last device $device from table $table: $deviceBom")
    } yield (deviceBom)
  }

  private[v1] def getDevAll(device: DeviceName, table: Table, from: Option[EpochSecTimestamp], to: Option[EpochSecTimestamp]): F[Iterable[DeviceV1]] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      deviceBoms <- repository.selectDevicesWhereTimestamp(table, device, from, to).map(_.map(DeviceV1.fromBom))
      _ <- logger.debug(s"GET all devices $device from table $table from time $from until $to: $deviceBoms")
    } yield (deviceBoms)
  }

  private[v1] def getDevActorTups(device: DeviceName, actor: Option[ActorName], table: Table, status: Option[Status], clean: Option[Boolean]): F[Iterable[ActorTup]] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      actorTups <- repository.selectActorTupWhereDeviceActorStatus(table, device, actor, status, clean.exists(identity)).compile.toList
      _ <- logger.debug(s"GET actor tups of device $device actor $actor from table $table with status $status cleaning $clean: $actorTups")
    } yield (actorTups)
  }

  private[v1] def getLastDevActorTups(device: DeviceName, actor: ActorName, table: Table, status: Option[Status]) = {
    repository.selectMaxActorTupsStatus(table, device, actor, status)
  }

  private[v1] def getDevActors(device: DeviceName, actor: ActorName, table: Table, status: Option[Status], clean: Option[Boolean]): F[Iterable[PropsMapV1]] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      actorTups <- repository.selectActorTupWhereDeviceActorStatus(table, device, Some(actor), status, clean.exists(identity)).compile.toList
      propsMaps = actorTups.groupBy(_.requestId).toList.sortBy(_._1)
      propsMapsU = propsMaps.map(v => PropsMapV1.fromTups(v._2))
      _ <- logger.debug(s"GET device actors device $device actor $actor from table $table with status $status and clean $clean: $propsMaps ($actorTups)")
    } yield (propsMapsU)
  }


  private[v1] def getDevActorCount(device: DeviceName, actor: Option[ActorName], table: Table, status: Option[Status]): F[CountResponse] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      actorTups <- repository.selectActorTupWhereDeviceActorStatus(table, device, actor, status, false).compile.toList
      count = CountResponse(actorTups.size)
      _ <- logger.debug(s"GET count of device $device actor $actor from table $table with status $status: $count ($actorTups)")
    } yield (count)
  }


  private[v1] def logAuthentication(user: AccessAttempt): F[AccessAttempt] = {
    for {
      logger <- Slf4jLogger.fromClass[F](Service.getClass)
      msg = user match {
        case Right(i) => s">>> Authenticated: ${i.name} ${i.email}"
        case Left(m) => s">>> Failed to authenticate: $m"
      }
      _ <- logger.debug(msg)
    } yield (user)
  }

  private[v1] val onFailure: AuthedService[String, F] = Kleisli(req => OptionT.liftF(Forbidden(req.authInfo)))
  private[v1] val customAuthMiddleware: AuthMiddleware[F, User] =
    AuthMiddleware(Kleisli(auth.authenticateAndCheckAccessFromRequest) andThen Kleisli(logAuthentication), onFailure)
  val serviceWithAuthentication: HttpService[F] = customAuthMiddleware(service)
  private[v1] def request(r: Request[F]): F[Response[F]] = serviceWithAuthentication.orNotFound(r)

  private def nowAtTimezone(tz: String): F[TimeResponse] = {
    val tutc = time.nowUtc.map(_.withZoneSameInstant(ZoneId.of(tz)))
    tutc.map(t => TimeResponse(tz, Time.asTimestamp(t), Time.asString(t)))
  }

}

object Service {

  final val ContentTypeAppJson = `Content-Type`(MediaType.`application/json`)
  final val ContentTypeTextPlain = `Content-Type`(MediaType.`text/plain`)

  case class IdResponse(id: RecordId)

  case class CountResponse(count: Int)

  case class TimeResponse(zoneName: String, timestamp: Long, formatted: String) //"zoneName":"Europe\/Paris","timestamp":1547019039,"formatted":"2019-01-09 07:30:39"}

}

