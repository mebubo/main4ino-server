package org.mauritania.botinobe.api.v1

import cats.effect.IO
import org.mauritania.botinobe.{DbSuite, Repository}
import org.http4s.{EntityBody, Header, Headers, HttpService, MediaType, Method, Request, Response, Uri}
import fs2.Stream
import io.circe.Json
import org.mauritania.botinobe.api.v1.Service.{CountResponse, IdResponse}
import io.circe.syntax._
import org.http4s.circe._
import io.circe.generic.auto._
import org.mauritania.botinobe.security.{Authentication, Config, User}
import org.scalatest.Sequential

class ServiceFuncSpec extends DbSuite {

  val Token = "012345678901234567890123456789"
  val AuthConfig = Config(List(User(1, "name", "name@gmail.com", List("/"), Token)))

  Sequential

  "The service" should "create and read a target/report" in {

    implicit val s = new Service(new Authentication(AuthConfig), new Repository(transactor))

    // Add a few targets
    post("/devices/dev1/targets", """{"clock": {"h":"7"}}""").noSpaces shouldBe IdResponse(1).asJson.noSpaces
    post("/devices/dev1/targets", """{"clock": {"m":"0"}}""").noSpaces shouldBe IdResponse(2).asJson.noSpaces
    post("/devices/dev1/targets", """{"clock": {"s":"1"}}""").noSpaces shouldBe IdResponse(3).asJson.noSpaces
    post("/devices/dev1/targets", """{"body": {"mv0":"Zz."}}""").noSpaces shouldBe IdResponse(4).asJson.noSpaces
    post("/devices/dev1/targets", """{"body": {"mv0":"ZzY"}}""").noSpaces shouldBe IdResponse(5).asJson.noSpaces
    post("/devices/dev2/targets", """{"body": {"mv0":"Zz."}}""").noSpaces shouldBe IdResponse(6).asJson.noSpaces // dev2
    post("/devices/dev1/targets", """{"clock": {"s":"9"}}""").noSpaces shouldBe IdResponse(7).asJson.noSpaces

    // Check the responses

    // Only the count of targets for dev1
    val dev1TargetsCount = get("/devices/dev1/actors/clock/targets/count?status=C")
    dev1TargetsCount.noSpaces shouldBe CountResponse(4).asJson.noSpaces

    // The raw targets list for dev1 / clock
    val dev1ClockTarget = get("/devices/dev1/actors/clock/targets?status=C&consume=false")
    dev1ClockTarget.asArray.get(0).noSpaces shouldBe("""{"h":"7"}""")
    dev1ClockTarget.asArray.get(1).noSpaces shouldBe("""{"m":"0"}""")
    dev1ClockTarget.asArray.get(2).noSpaces shouldBe("""{"s":"1"}""")
    dev1ClockTarget.asArray.get(3).noSpaces shouldBe("""{"s":"9"}""")

    val dev1ClockBody = get("/devices/dev1/actors/body/targets?status=C&consume=false")
    dev1ClockBody.asArray.get(0).noSpaces shouldBe("""{"mv0":"Zz."}""")
    dev1ClockBody.asArray.get(1).noSpaces shouldBe("""{"mv0":"ZzY"}""")

    // The merged targets for dev1
    val dev1TargetsSummaryBody = get("/devices/dev1/actors/body/targets/summary?consume=false&status=C")
    dev1TargetsSummaryBody.noSpaces shouldBe """{"mv0":"ZzY"}"""

    val dev1TargetsSummaryClock = get("/devices/dev1/actors/clock/targets/summary?consume=false&status=C")
    dev1TargetsSummaryClock.noSpaces shouldBe """{"h":"7","s":"9","m":"0"}"""

    val dev1TargetsMergedCleanedBody = get("/devices/dev1/actors/body/targets/summary?consume=true&status=C")
    dev1TargetsMergedCleanedBody.noSpaces shouldBe """{"mv0":"ZzY"}"""

    val dev1TargetsMergedCleanedClock = get("/devices/dev1/actors/clock/targets/summary?consume=true&status=C")
    dev1TargetsMergedCleanedClock.noSpaces shouldBe """{"h":"7","s":"9","m":"0"}"""

    val dev1TargetsCountAfterClean = get("/devices/dev1/actors/body/targets/count?status=C")
    dev1TargetsCountAfterClean.noSpaces shouldBe CountResponse(0).asJson.noSpaces

  }

  it should "create targets and merge the properties correctly" in {

    implicit val s = new Service(new Authentication(AuthConfig), new Repository(transactor))

    // Add a few targets
    post("/devices/dev1/targets", """{"clock":{"h":"7"},"body":{"mv0":"Zz."}}""").noSpaces shouldBe IdResponse(1).asJson.noSpaces
    post("/devices/dev1/targets", """{"clock":{"m":"0"}}""").noSpaces shouldBe IdResponse(2).asJson.noSpaces
    post("/devices/dev1/targets", """{"body":{"mv1":"Zz."}}""").noSpaces shouldBe IdResponse(3).asJson.noSpaces

    // Check the responses
    val clk = get("/devices/dev1/actors/clock/targets/summary?consume=false&count=false&status=C")
    val body = get("/devices/dev1/actors/body/targets/summary?consume=false&count=false&status=C")

    clk.noSpaces shouldBe """{"h":"7","m":"0"}"""
    body.noSpaces shouldBe """{"mv1":"Zz.","mv0":"Zz."}"""

  }

  private[this] def get(path: String)(implicit service: Service): Json = {
    val request = Request[IO](method = Method.GET, uri = Uri.unsafeFromString(path), headers = DefaultHeaders)
    service.request(request).unsafeRunSync().as[Json].unsafeRunSync()
  }

  private[this] def post(path: String, body: String)(implicit service: Service): Json = {
    val request = Request[IO](method = Method.POST, uri = Uri.unsafeFromString(path), body = asEntityBody(body), headers = DefaultHeaders)
    service.request(request).unsafeRunSync().as[Json].unsafeRunSync()
  }

  private [this] def asEntityBody(content: String): EntityBody[IO] = {
    Stream.fromIterator[IO, Byte](content.toCharArray.map(_.toByte).toIterator)
  }

  final val DefaultHeaders = Headers(Header("Authorization", "token " + Token))

}
