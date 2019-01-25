package org.mauritania.main4ino

import cats.effect.IO
import io.circe.Json
import org.http4s.client.blaze.Http1Client
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.{BasicCredentials, Method, Request, Uri}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, Sequential}
import io.circe.syntax._
import org.http4s.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe._
import io.circe.parser._
import org.mauritania.main4ino.api.v1.DeviceV1
import org.mauritania.main4ino.api.v1.Service.IdResponse
import org.mauritania.main4ino.models.RecordId

class ServerSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  Sequential
  var appThread: Thread = _
  var httpClient: Client[IO] = _
  val UserPass = BasicCredentials(Fixtures.User1.id, Fixtures.User1Pass)

  override def beforeAll(): Unit = {
    appThread = launchAsync(Array("src/test/resources/config01"))
    httpClient = Http1Client[IO]().unsafeRunSync
    Thread.sleep(3000)
  }

  override def afterAll(): Unit = {
    appThread.interrupt()
    httpClient.shutdownNow()
  }

  "The server" should "start and expose rest the api (v1)" in {
    val help = httpClient.expect[String](s"http://localhost:8080/api/v1/token/${UserPass.token}/help")
    help.unsafeRunSync() should include("HELP")

  }

  it should "reject unauthorized requests" in {
    assertThrows[UnexpectedStatus] { // forbidden
      httpClient.expect[String]("http://localhost:8080/api/v1/help").unsafeRunSync()
    }
  }

  it should "perform cleanup of old entries regularly" in {

    // inject dev1
    val dev1ResponseJson = httpClient.expect[String](devPostRequest("dev1", "targets"))
    val id1 = jsonAs[IdResponse](dev1ResponseJson.unsafeRunSync()).id

    // check that dev1 exists
    val dev1t0 = httpClient.expect[String](devGetRequest("dev1", "targets", id1))
    jsonAs[DeviceV1](dev1t0.unsafeRunSync()).metadata.id shouldBe Some(id1)

    Thread.sleep(2000)

    // check that dev1 still exists
    val dev1t2 = httpClient.expect[String](devGetRequest("dev1", "targets", id1))
    jsonAs[DeviceV1](dev1t2.unsafeRunSync()).metadata.id shouldBe Some(id1)

    // inject dev2
    val dev2ResponseJson = httpClient.expect[String](devPostRequest("dev2", "targets"))
    val id2 = jsonAs[IdResponse](dev2ResponseJson.unsafeRunSync()).id

    // check that dev2 exists
    val dev2t2 = httpClient.expect[String](devGetRequest("dev2", "targets", id2))
    jsonAs[DeviceV1](dev2t2.unsafeRunSync()).metadata.id shouldBe Some(id2)

    Thread.sleep(3000)
    // cleanup every 5s
    Thread.sleep(1000)

    // check that dev2 exists
    val dev2t6 = httpClient.expect[String](devGetRequest("dev2", "targets", id2))
    jsonAs[DeviceV1](dev2t6.unsafeRunSync()).metadata.id shouldBe Some(id2)

    // check that dev1 does not exist anymore (cleaned up)
    val dev1t6 = httpClient.expect[String](devGetRequest("dev1", "targets", id1))
    jsonAs[DeviceV1](dev1t6.unsafeRunSync()).metadata.id shouldBe Some(id1)

  }

  private def jsonAs[T](json: String)(implicit v: Decoder[T]): T = { // TODO replace
    parse(json).toOption.flatMap(_.as[T].toOption).get
  }

  private def devPostRequest(devName: String, table: String) = {
    Request[IO](
      method = Method.POST,
      uri = Uri.unsafeFromString(s"http://localhost:8080/api/v1/token/${UserPass.token}/devices/$devName/$table"),
      body = Helper.asEntityBody[IO]("""{"actor1":{"prop1":"val1"}}""")
    )
  }

  private def devGetRequest(devName: String, table: String, id: RecordId) = {
    Request[IO](
      method = Method.GET,
      uri = Uri.unsafeFromString(s"http://localhost:8080/api/v1/token/${UserPass.token}/devices/$devName/$table/$id")
    )
  }

  it should "start and expose the webapp files" in {
    val help = httpClient.expect[String](s"http://localhost:8080/index.html")
    help.unsafeRunSync() should include("</body>")
  }

  private def launchAsync(args: Array[String]): Thread = {
    val runnable = new Runnable() {
      override def run() = {
        Server.main(args)
      }
    }
    val thread = new Thread(runnable)
    thread.start()
    thread
  }

}
