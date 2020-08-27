package controllers

import akka.stream.scaladsl.{Flow, Sink, Source}
import com.gu.pandomainauth.PublicSettings
import model.{Check, MatcherResponse}
import actor.{WsCheckActor}
import play.api.Logger
import play.api.libs.json.{JsValue, Json}
import play.api.mvc._
import services.{PandaAuthentication, MatcherPool}

import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.streams.ActorFlow

/**
  * The controller that handles API requests.
  */
class ApiController(
  cc: ControllerComponents,
  matcherPool: MatcherPool,
  val publicSettings: PublicSettings
)(implicit ec: ExecutionContext) extends AbstractController(cc) with PandaAuthentication {

  def check: Action[JsValue] = ApiAuthAction.async(parse.json) { request =>
    request.body.validate[Check].asEither match {
      case Right(check) =>
        matcherPool
          .check(check)
          .map(response => Ok(Json.toJson(response))) recover {
            case e: Exception =>
              InternalServerError(Json.obj("error" -> e.getMessage))
          }
      case Left(error) =>
        Future.successful(BadRequest(s"Invalid request: $error"))
    }
  }

  def checkStream = WebSocket.accept[JsValue, JsValue] { request =>
    ActorFlow.actorRef { out =>
      WsCheckActor.props(out, matcherPool)
    }
  }

  def getCurrentCategories: Action[AnyContent] = ApiAuthAction {
      Ok(Json.toJson(matcherPool.getCurrentCategories.map(_._2)))
  }
}
