package org.mauritania.botinobe.models

import org.mauritania.botinobe.models.ActorMap.ActorMap
import org.mauritania.botinobe.models.Device.Metadata
import org.mauritania.botinobe.models.PropsMap.PropsMap

case class Device(
	metadata: Metadata,
	actors: ActorMap = Device.EmptyActorMap
) {

	def asActorTups: Iterable[ActorTup] =
		for {
			(actor, ps) <- actors.toSeq
			(propName, (propValue, status)) <- ps.toSeq
		} yield (ActorTup(None, metadata.device, actor, propName, propValue, status))

	def asTuples: Iterable[ActorTup] = {
		this.asActorTups.map(p => ActorTup(metadata.id, metadata.device, p.actor, p.prop, p.value, p.status))
	}

	def withId(i: Option[RecordId]): Device = this.copy(metadata = this.metadata.copy(id = i))
	def withDeviceName(n: DeviceName): Device = this.copy(metadata = this.metadata.copy(device = n))
	def withStatus(s: Status): Device = Device.fromActorTups(metadata, asActorTups.map(_.copy(status = s)))
	def withTimestamp(t: Option[Timestamp]): Device = this.copy(metadata = this.metadata.copy(timestamp = t))
	def withouIdNortTimestamp(): Device = this.copy(metadata = this.metadata.copy(id = None, timestamp = None))

}

object Device {

	val EmptyActorMap: ActorMap = Map.empty[ActorName, PropsMap]

	case class Metadata ( // TODO metadata information that comes after DB insertion should be put on a wrapper of Device to avoid using .copy
		id: Option[RecordId],
		timestamp: Option[Timestamp],
    device: DeviceName
	)

	def fromActorTups(metadata: Metadata, ps: Iterable[ActorTup]): Device = Device(metadata, ActorMap.fromTups(ps))

}

