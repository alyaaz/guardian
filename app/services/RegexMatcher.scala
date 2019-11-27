package services

import model.{RegexRule, RuleMatch}
import play.api.Logger
import utils.Matcher

import scala.concurrent.{ExecutionContext, Future}

class RegexMatcher(category: String, rules: List[RegexRule]) extends Matcher {
  def getId() = "regex-validator"

  override def check(request: MatcherRequest)(implicit ec: ExecutionContext): Future[List[RuleMatch]] = {
    Future {
      Logger.info(s"Running regex matcher ${request.blocks.map(_.id).mkString(",")} on thread ${Thread.currentThread().getName}")
      rules.flatMap {
        checkRule(request, _)
      }
    }
  }

  override def getRules(): List[RegexRule] = rules

  override def getCategory(): String = category

  private def checkRule(request: MatcherRequest, rule: RegexRule): List[RuleMatch] = {
    request.blocks.flatMap { block =>
        rule.regex.findAllMatchIn(block.text).map { currentMatch => RuleMatch(
          rule = rule,
          fromPos = currentMatch.start + block.from,
          toPos = currentMatch.end + block.from,
          message = rule.description,
          shortMessage = Some(rule.description),
          suggestions = rule.suggestions,
          markAsCorrect = rule.replacement.map(_.text).getOrElse("") == block.text.substring(currentMatch.start, currentMatch.end)
        )
      }
    }
  }
}