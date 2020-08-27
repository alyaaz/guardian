package actor

import model.{Check, MatcherWorkComplete, MatcherError}
import play.api.libs.json.{JsValue, Json}
import services.{MatcherPool}

import scala.concurrent.ExecutionContext
import akka.actor._
import play.api.libs.json.JsResult
import play.api.libs.json.JsSuccess

object WsCheckActor {
  def props(out: ActorRef, pool: MatcherPool)(implicit ec: ExecutionContext) = Props(new WsCheckActor(out, pool))
}

class WsCheckActor(out: ActorRef, pool: MatcherPool)(implicit ec: ExecutionContext) extends Actor {
  def receive: PartialFunction[Any, Unit] = {
    case jsValue: JsValue =>
      jsValue.validate[Check] match {
        case JsSuccess(check, _) => pool.checkStream(check).map { out ! _ }
        case _ =>
          out ! Json.toJson(MatcherError("Error parsing input"))
          out ! PoisonPill
      }
  }

  // private def onEvent(event: MatcherPoolEvent): Unit = event match {
  //   case MatcherPoolResultEvent(_, response) => {
  //     println("sending", response.blocks.map(_.id).mkString(", "), response.categoryIds.mkString(", "))
  //     out ! Json.toJson(response)
  //   }
  //   case _: MatcherPoolJobsCompleteEvent => {
  //     out ! Json.toJson(ValidatorWorkComplete())
  //     out ! PoisonPill
  //   }
  // }
}
