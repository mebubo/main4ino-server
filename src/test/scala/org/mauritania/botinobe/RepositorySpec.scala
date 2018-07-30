package org.mauritania.botinobe.models

import org.mauritania.botinobe.{DbSuite, Repository}
import org.mauritania.botinobe.Fixtures.Device1
import org.mauritania.botinobe.Repository.Table

class RepositorySpec extends DbSuite {

  "The repository" should "create and read a target/report" in {
    val repo = new Repository(transactor)

    repo.createDevice(Table.Reports, Device1).unsafeRunSync() shouldBe(1L)
    repo.readDevice(Table.Reports, 1L).unsafeRunSync() shouldBe(Device1.withId(Some(1L)))

    repo.createDevice(Table.Targets, Device1).unsafeRunSync() shouldBe(1L)
    repo.readDevice(Table.Targets, 1L).unsafeRunSync() shouldBe(Device1.withId(Some(1L)))

  }

  it should "read target/report ids from a device name" in {
    val repo = new Repository(transactor)

    val t1 = Device1.withDeviceName("device1")
    val t2 = Device1.withDeviceName("device2")

    Table.all.foreach { table =>
      repo.createDevice(table, t1).unsafeRunSync() shouldBe(1L) // created target for device 1, resulted in id 1
      repo.createDevice(table, t2).unsafeRunSync() shouldBe(2L) // for device 2, resulted in id 2
      repo.createDevice(table, t2).unsafeRunSync() shouldBe(3L) // for device 2, resulted in id 3

      repo.readRequestIds(table, t1.metadata.device).compile.toList.unsafeRunSync() shouldBe(List(1L))
      repo.readRequestIds(table, t2.metadata.device).compile.toList.unsafeRunSync() shouldBe(List(2L, 3L))
    }
  }

  it should "read target/report ids and update them as consumed" in {
    val repo = new Repository(transactor)
    Table.all.foreach { table =>
      val ref = Device1.withId(Some(1L))
      repo.createDevice(table, Device1).unsafeRunSync() shouldBe 1L

      repo.readDevice(table, 1L).unsafeRunSync() shouldBe ref.withStatus(Status.Created)
      repo.readPropsConsume(table, Device1.metadata.device, "actorx", Status.Created).compile.toList.unsafeRunSync() shouldBe ref.withStatus(Status.Consumed)
    }
  }

}
