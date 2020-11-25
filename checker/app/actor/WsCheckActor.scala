package actor

import model.{Check, CheckResult, MatcherWorkComplete, MatcherError}
import play.api.libs.json.{JsValue, Json}
import services.{MatcherPool}

import scala.concurrent.ExecutionContext
import akka.actor._
import play.api.libs.json.JsResult
import play.api.libs.json.JsSuccess
import akka.stream.scaladsl.Sink
import akka.stream.Materializer
import model.MatcherResponse
import model.RuleMatch

object WsCheckActor {
  def props(out: ActorRef, pool: MatcherPool)(implicit ec: ExecutionContext, mat: Materializer) = Props(new WsCheckActor(out, pool))
}

class WsCheckActor(out: ActorRef, pool: MatcherPool)(implicit ec: ExecutionContext, mat: Materializer) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case jsValue: JsValue =>
      jsValue.validate[Check] match {
        case JsSuccess(check, _) =>
          pool.checkStream(check)
            .map(out ! streamResultToResponse(_))
            .to(Sink.ignore)
            .run()
        case _ =>
          out ! Json.toJson(MatcherError("Error parsing input"))
          out ! PoisonPill
      }
  }

  def streamResultToResponse(result: CheckResult) =
    Json.toJson(MatcherResponse.fromCheckResult(result))
}
