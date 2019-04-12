package org.mauritania.main4ino

import cats.effect.IO
import cats.free.Free
import cats.implicits._
import doobie._
import doobie.free.connection.{ConnectionOp, raw}
import doobie.implicits._
import doobie.util.transactor.Transactor
import fs2.Stream
import org.mauritania.main4ino.Repository.{ActorTup, ActorTupIdLess, Attempt, Device1}
import org.mauritania.main4ino.Repository.ReqType.ReqType
import org.mauritania.main4ino.api.ErrMsg
import org.mauritania.main4ino.models.Device.Metadata.Status
import org.mauritania.main4ino.models.Device.{DbId, Metadata}
import org.mauritania.main4ino.models.Device.Metadata.Status.Status
import org.mauritania.main4ino.models._

trait Repository[F[_]] {

  def insertDeviceActor(table: ReqType, device: DeviceName, actor: ActorName, requestId: RequestId, r: ActorProps, ts: EpochSecTimestamp): F[Attempt[Int]]

  def cleanup(table: ReqType, now: EpochSecTimestamp, preserveWindowSecs: Int): F[Int]

  def deleteDeviceWhereName(table: ReqType, device: DeviceName): F[Int]

  def insertDevice(table: ReqType, t: Device, ts: EpochSecTimestamp): F[RequestId]

  def selectDeviceWhereRequestId(table: ReqType, dev: DeviceName, requestId: RequestId): F[Attempt[DeviceId]]

  def selectDevicesWhereTimestampStatus(table: ReqType, device: DeviceName, from: Option[EpochSecTimestamp], to: Option[EpochSecTimestamp], st: Option[Status]): F[Iterable[DeviceId]]

  def selectMaxDevice(table: ReqType, device: DeviceName, status: Option[Status]): F[Option[DeviceId]]

  def selectRequestIdsWhereDevice(table: ReqType, d: DeviceName): Stream[F, RequestId]

  def updateDeviceWhereRequestId(table: ReqType, dev: DeviceName, requestId: RequestId, status: Status): F[Either[ErrMsg,Int]]

}

// Naming regarding to SQL
class RepositoryIO(transactor: Transactor[IO]) extends Repository[IO] {

  implicit val StatusMeta: Meta[Status] = Meta[String].xmap(Status.apply, _.code)

  def cleanup(table: ReqType, now: EpochSecTimestamp, retentionSecs: Int) = {
    val transaction = for {
      m <- sqlDeleteMetadataWhereCreationIsLess(table, now - retentionSecs)
      _ <- sqlDeleteActorTupOrphanOfRequest(table)
    } yield (m)
    transaction.transact(transactor)
  }

  def deleteDeviceWhereName(table: ReqType, device: String) = {
    val transaction = for {
      m <- sqlDeleteMetadataWhereDeviceName(table, device)
      _ <- sqlDeleteActorTupOrphanOfRequest(table)
    } yield (m)
    transaction.transact(transactor)
  }

  def insertDevice(table: ReqType, t: Device, ts: EpochSecTimestamp): IO[RequestId] = {
    // TODO potential optimization: do all in one single sql query
    val transaction = for {
      deviceId <- sqlInsertMetadata(table, t.metadata, ts)
      _ <- sqlInsertActorTup(table, t.asActorTups, deviceId, ts)
    } yield (deviceId)
    transaction.transact(transactor)
  }

  def insertDeviceActor(table: ReqType, dev: DeviceName, actor: ActorName, requestId: RequestId, p: ActorProps, ts: EpochSecTimestamp): IO[Attempt[Int]] = {
    val transaction = for {
      mtd <- sqlSelectMetadataWhereRequestId(table, requestId)
      safe = mtd.exists{case (i, m) => m.device == dev}
      transit = mtd.exists{case (i, m) => m.status == Status.Open}
      inserts: ConnectionIO[Attempt[Int]] = if (!safe)
        Free.pure[ConnectionOp, Attempt[Int]](Left.apply[ErrMsg, Int](s"Request $requestId does not relate to $dev"))
      else if (!transit)
        Free.pure[ConnectionOp, Attempt[Int]](Left.apply[ErrMsg, Int](s"Request $requestId is not open"))
      else
        sqlInsertActorTup(table, ActorTup.from(dev, actor, p), requestId, ts).map(Right.apply[ErrMsg, Int])
      attempt <- inserts
    } yield (attempt)
    transaction.transact(transactor)
  }

  private def transitionAllowed(current: Status, target: Status): Boolean = {
    (current, target) match {
      case (Status.Open, Status.Closed) => true
      case (Status.Closed, Status.Consumed) => true
      case _ => false
    }
  }

  def updateDeviceWhereRequestId(table: ReqType, dev: DeviceName, requestId: RequestId, status: Status): IO[Either[ErrMsg,Int]] = {
    val transaction = for {
      mtd <- sqlSelectMetadataWhereRequestId(table, requestId)
      safe = mtd.exists{case (i, m) => (m.device == dev)}
      trans = mtd.exists{case (i, m) => transitionAllowed(m.status, status)}
      inserts: ConnectionIO[Attempt[Int]] = if (!safe)
        Free.pure[ConnectionOp, Attempt[Int]](Left.apply[ErrMsg, Int](s"Request $requestId does not relate to $dev"))
      else if (!trans)
        Free.pure[ConnectionOp, Attempt[Int]](Left.apply[ErrMsg, Int](s"State transition not allowed"))
      else
        sqlUpdateMetadataWhereRequestId(table, requestId, status).map(Right.apply[ErrMsg, Int])
      nroInserts <- inserts
    } yield (nroInserts)
    transaction.transact(transactor)

  }

  def selectDeviceWhereRequestId(table: ReqType, dev: DeviceName, requestId: RequestId): IO[Attempt[DeviceId]] = {
    val transaction = for {
      t <- sqlSelectMetadataWhereRequestId(table, requestId)
      k = t.exists{case (i, m) => m.device == dev}
      p <- sqlSelectActorTupWhereRequestIdActorStatus(table, requestId)
      j = t.map{case (i, m) => Repository.toDevice(i, m, p)}.filter(_.metadata.device == dev).toRight(s"Request $requestId does not belong to $dev")
    } yield (j)
    transaction.transact(transactor)
  }

  def selectDevicesWhereTimestampStatus(table: ReqType, device: DeviceName, from: Option[EpochSecTimestamp], to: Option[EpochSecTimestamp], st: Option[Status]): IO[Iterable[DeviceId]] = {
    val transaction = for {
      d <- sqlSelectMetadataActorTupWhereDeviceStatus(table, device, from, to, st)
    } yield (d)
    val s = transaction.transact(transactor)
    val iol = s.compile.toList
    iol.map(l => Device1.asDeviceHistory(l).toSeq.sortBy(_.dbId.creation))
  }

  def selectMaxDevice(table: ReqType, device: DeviceName, status: Option[Status]): IO[Option[DeviceId]] = {
    val transaction = for {
      i <- sqlSelectLastRequestIdWhereDeviceStatus(table, device, status)
      t <- i.map(sqlSelectMetadataWhereRequestId(table, _)).getOrElse(raw[Option[(DbId, Metadata)]](x => None))
      p <- i.map(sqlSelectActorTupWhereRequestIdActorStatus(table, _)).getOrElse(raw[List[ActorTup]](x => List.empty[ActorTup]))
    } yield {
      (t, p) match {
        case (Some((i, m)), l) => Some(Repository.toDevice(i, m, l))
        case _ => None
      }
    }
    transaction.transact(transactor)
  }

  def selectRequestIdsWhereDevice(table: ReqType, d: DeviceName): Stream[IO, RequestId] = {
    (fr"SELECT id FROM " ++ Fragment.const(table.code + "_requests") ++ fr" WHERE device_name=$d").query[RequestId].stream.transact(transactor)
  }

  // SQL queries (private)

  private def sqlInsertActorTup(table: ReqType, tups: Iterable[ActorTupIdLess], requestId: RequestId, ts: EpochSecTimestamp): ConnectionIO[Int] = {
    val sql = s"INSERT INTO ${table.code} (request_id, actor_name, property_name, property_value, creation) VALUES (?, ?, ?, ?, ?)"
    Update[ActorTup](sql).updateMany(tups.toList.map(t => ActorTup(requestId, t, ts)))
  }

  private def sqlInsertMetadata(table: ReqType, m: Metadata, ts: EpochSecTimestamp): ConnectionIO[RequestId] = {
    (fr"INSERT INTO " ++ Fragment.const(table.code + "_requests") ++ fr" (creation, device_name, status) VALUES (${ts}, ${m.device}, ${m.status})")
      .update.withUniqueGeneratedKeys[RequestId]("id")
  }

  private def sqlUpdateMetadataWhereRequestId(table: ReqType, r: RequestId, s: Status): ConnectionIO[Int] = {
    (fr"UPDATE " ++ Fragment.const(table.code + "_requests") ++ fr" SET status = ${s} WHERE id=${r}").update.run
  }

  private def sqlDeleteMetadataWhereDeviceName(table: ReqType, device: DeviceName): ConnectionIO[Int] = {
    (fr"DELETE FROM" ++ Fragment.const(table.code + "_requests") ++ fr"WHERE device_name=$device")
      .update.run
  }

  private def sqlDeleteMetadataWhereCreationIsLess(table: ReqType, upperbound: EpochSecTimestamp): ConnectionIO[Int] = {
    (fr"DELETE FROM" ++ Fragment.const(table.code + "_requests") ++ fr"WHERE creation < $upperbound")
      .update.run
  }

  private def sqlDeleteActorTupOrphanOfRequest(table: ReqType): ConnectionIO[Int] = {
    (fr"DELETE FROM " ++ Fragment.const(table.code) ++ fr" tu WHERE NOT EXISTS (SELECT 1 FROM " ++ Fragment.const(table.code + "_requests") ++ fr" re where tu.request_id = re.id)")
      .update.run
  }

  private def sqlSelectMetadataActorTupWhereDeviceStatus(table: ReqType, d: DeviceName, from: Option[EpochSecTimestamp], to: Option[EpochSecTimestamp], st: Option[Status]): Stream[ConnectionIO, Device1] = {
    val fromFr = from match {
      case Some(a) => fr"AND r.creation >= $a"
      case None => fr""
    }
    val toFr = to match {
      case Some(a) => fr"AND r.creation <= $a"
      case None => fr""
    }
    val stFr = st match {
      case Some(s) => fr"AND r.status = $s"
      case None => fr""
    }
    (fr"SELECT r.id, r.creation, r.device_name, r.status, t.request_id, t.actor_name, t.property_name, t.property_value, t.creation" ++
      fr"FROM" ++ Fragment.const(table.code + "_requests") ++ fr"as r LEFT OUTER JOIN" ++ Fragment.const(table.code) ++ fr"as t" ++
      fr"ON r.id = t.request_id" ++
      fr"WHERE r.device_name=$d" ++ fromFr ++ toFr ++ stFr)
      .query[Device1].stream
  }

  private def sqlSelectMetadataWhereRequestId(table: ReqType, id: RequestId): ConnectionIO[Option[(DbId, Metadata)]] = {
    (fr"SELECT id, creation, device_name, status FROM " ++ Fragment.const(table.code + "_requests") ++ fr" WHERE id=$id")
      .query[(DbId, Metadata)].option
  }

  private def sqlSelectLastRequestIdWhereDeviceStatus(table: ReqType, device: DeviceName, status: Option[Status]): ConnectionIO[Option[RequestId]] = {
    val statusFr = status match {
      case Some(s) => fr"AND status = $s"
      case None => fr""
    }
    (fr"SELECT MAX(id) FROM " ++ Fragment.const(table.code + "_requests") ++ fr" WHERE device_name=$device" ++ statusFr).query[Option[RequestId]].unique
  }

  private def sqlSelectActorTupWhereRequestIdActorStatus(
    table: ReqType,
    requestId: RequestId,
    actor: Option[ActorName] = None
  ): ConnectionIO[List[ActorTup]] = {
    val actorFr = actor match {
      case Some(a) => fr"AND actor_name = $a"
      case None => fr""
    }
    (fr"SELECT request_id, actor_name, property_name, property_value, creation FROM " ++ Fragment.const(table.code) ++ fr" WHERE request_id=$requestId" ++ actorFr)
      .query[ActorTup].accumulate
  }

}

object Repository {

  type Attempt[T] = Either[ErrMsg, T]

  def toDevice(dbId: DbId, metadata: Metadata, ats: Iterable[ActorTup]): DeviceId = DeviceId(dbId, Device(metadata, ActorTup.asActorMap(ats)))

  /**
    * Intermediate device representation
    *
    * It contains a single actor tuple. Instances of this class
    * are meant to be aggregated to create instances of [[Device]].
    *
    * @param dbId       the database related ids (and creation timestamp)
    * @param metadata   the metadata
    * @param actorTuple the single actor tuple (if present, there may be a metadata without any property)
    */
  case class Device1(
    dbId: DbId,
    metadata: Metadata,
    actorTuple: Option[ActorTup] // optional (if device filled up)
  )

  object Device1 {

    def asDeviceHistory(s: Iterable[Device1]): Iterable[DeviceId] = {
      val g = s.groupBy(_.dbId)
      val dh = g.map { case (id, d1s) => DeviceId(id, Device(d1s.head.metadata, ActorTup.asActorMap(d1s.flatMap(_.actorTuple)))) }
      dh
    }

  }

  object ReqType {

    sealed abstract class ReqType(val code: String)

    case object Reports extends ReqType("reports")

    case object Targets extends ReqType("targets")

    val all = List(Reports, Targets)

    def resolve(s: String): Option[ReqType] = all.find(_.code == s)
  }

  case class ActorTup(
    requestId: RequestId,
    more: ActorTupIdLess,
    creation: EpochSecTimestamp
  ) {
    def actor = more.actor
    def prop = more.prop
    def value = more.value
  }

  case class ActorTupIdLess(
    actor: ActorName,
    prop: PropName,
    value: PropValue
  )

  object ActorTup {

    def apply(id: RequestId, actor: ActorName, prop: PropName, value: PropValue, creation: EpochSecTimestamp): ActorTup =
      ActorTup(id, ActorTupIdLess(actor, prop, value), creation)

    def asActorMap(ats: Iterable[ActorTup]): DeviceProps = {
      ats.groupBy(_.actor).mapValues{ byActor =>
        val indexed = byActor.zipWithIndex
        val byProp = indexed.groupBy{case (t, i) => t.prop}
        byProp.mapValues{ a =>
          val (latestPropValue, _) = a.maxBy{case (t, i) => i}
          latestPropValue.value
        }
      }
    }

    def from(deviceName: DeviceName, actorName: ActorName, pm: ActorProps): Iterable[ActorTupIdLess] = {
      pm.map { case (name, value) =>
        ActorTupIdLess(actorName, name, value)
      }
    }
  }

}