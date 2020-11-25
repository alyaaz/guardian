package model

import play.api.libs.json._

case class MatcherResponse(blocks: List[TextBlock], categoryIds: Set[String], matches: List[RuleMatch]) {
  val `type` = "MATCHER_RESPONSE"
}

object MatcherResponse {
  implicit val writes = new Writes[MatcherResponse] {
    def writes(response: MatcherResponse) = Json.obj(
      "type" -> response.`type`,
      "categoryIds" -> response.categoryIds,
      "blocks" -> response.blocks,
      "matches" -> response.matches
    )
  }

  def fromCheckResult(result: CheckResult) =
    MatcherResponse(result.blocks, result.categoryIds, result.matches)
}
