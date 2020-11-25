package actor

import model.{Check, CheckResult, CheckComplete, CheckError}
import play.api.libs.json.{JsValue, Json}
import services.{MatcherPool}

import scala.concurrent.ExecutionContext
import akka.actor._
import play.api.libs.json.JsResult
import play.api.libs.json.JsSuccess
import akka.stream.scaladsl.Sink
import akka.stream.Materializer
import model.CheckResponse
import model.RuleMatch
import play.api.libs.json.JsError

object WsCheckActor {
  def props(out: ActorRef, pool: MatcherPool)(implicit ec: ExecutionContext, mat: Materializer) = Props(new WsCheckActor(out, pool))
}

class WsCheckActor(out: ActorRef, pool: MatcherPool)(implicit ec: ExecutionContext, mat: Materializer) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case jsValue: JsValue =>
      jsValue.validate[Check] match {
        case JsSuccess(check, _) =>
          val eventuallyStreamComplete = pool.checkStream(check)
            .map(out ! streamResultToResponse(_))
            .runWith(Sink.seq)

          eventuallyStreamComplete.map { _ => out ! streamComplete }
        case JsError(error) =>
          out ! Json.toJson(CheckError(s"Error parsing input: ${error.toString}"))
          out ! PoisonPill
      }
  }

  def streamResultToResponse(result: CheckResult) =
    Json.toJson(CheckResponse.fromCheckResult(result))

  def streamComplete = Json.toJson(CheckComplete())
}
