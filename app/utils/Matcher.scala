package utils

import model.{BaseRule, RuleMatch}
import services.MatcherRequest

import scala.concurrent.Future
import scala.concurrent.ExecutionContext

trait Matcher {
  def check(request: MatcherRequest)(implicit ec: ExecutionContext): Future[List[RuleMatch]]
  def getId(): String
  def getRules(): List[BaseRule]
  def getCategory(): String
}
